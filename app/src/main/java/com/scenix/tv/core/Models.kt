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
 * The app never invents content and never re-shapes this: the backend sends it, the standalone
 * host parses it, and the page reads the same JSON. Missing fields get neutral defaults so a
 * backend that adds or drops a field cannot crash the app (forward compatibility).
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
    /** Which episode of a series this stopped on, so Play resumes that episode instead of S01E01. */
    val episode: Episode? = null,
) {
    val fraction: Float
        get() = if (watchedMs <= 0L) 0f else (positionMs.toFloat() / watchedMs.toFloat()).coerceIn(0f, 1f)

    companion object {
        fun from(o: JSONObject?): Progress? = o?.let {
            val e = it.optJSONObject("episode")
            val se = if (e == null) null else Episode(
                e.optInt("season", 0), e.optInt("episode", 0), s(e, "title", ""),
            ).takeIf { q -> q.season > 0 && q.episode > 0 }
            Progress(it.optLong("positionMs", 0L), it.optLong("watchedMs", 0L), se)
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
 * One episode of a series. The hub lists these from the addon's own episode map and addresses streams
 * as `<show>:<season>:<episode>`, so playing a particular episode is only a matter of naming it in the
 * play request — the tile itself stays the series, which is what keeps rows, dedupe and Continue sane.
 */
data class Episode(val season: Int, val episode: Int, val title: String = "") {
    /** What a person reads on a button: "S1 E4". */
    fun label(): String = "S${season} E${episode}"

    /** What a release is named on disk and in the player's title line: "S01E04". */
    fun code(): String = "S%02dE%02d".format(season, episode)

    /** The episode line for the player: "S01E04 · Cripples, Bastards and Broken Things". */
    fun named(): String = if (title.isBlank()) code() else "${code()} \u00B7 $title"
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
    /**
     * Episodes of a series, when the hub knows them. A series is ONE tile with many addresses: this is
     * what lets the Play button say "Play S1 E1" instead of guessing, and what lets the player queue the
     * next episode instead of replaying the same one.
     */
    val episodes: List<Episode> = emptyList(),
    /** Optional hub annotation: subtitle languages it can serve. Drives the CC chip; absent = no chip. */
    val subtitleLanguages: List<String> = emptyList(),
) {
    /** Artwork to draw for a card of this item, given the card's aspect. */
    fun artFor(wide: Boolean): String? =
        (if (wide) backdrop ?: poster ?: logo else poster ?: logo ?: backdrop)?.takeIf { it.isNotBlank() }

    fun isLive(): Boolean = type == "channel" || live != null

    /** The episode a Play press means when nobody has named one: the earliest one the hub listed. */
    fun firstEpisode(): Episode? = episodes.minWithOrNull(compareBy({ it.season }, { it.episode }))

    /** "S1 E1" for a button; "" when this is not a series with a known episode list. */
    fun episodeLabel(): String = firstEpisode()?.label() ?: ""

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
            episodes = epList(o.optJSONArray("episodes")),
            subtitleLanguages = strList(o.optJSONArray("subtitleLanguages") ?: o.optJSONArray("subs")),
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

        private fun epList(a: JSONArray?): List<Episode> {
            if (a == null) return emptyList()
            val out = ArrayList<Episode>(a.length())
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                val s = o.optInt("season", 0)
                val e = o.optInt("episode", 0)
                if (s > 0 && e > 0) out.add(Episode(s, e, s(o, "title", "")))
            }
            return out
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
