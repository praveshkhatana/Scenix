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
import com.scenix.tv.core.stringList
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
 * One channel on the hub's live wall, in the zap order the remote walks. `number` is the hub's stable
 * channel number (it survives a restart); `health` is what the hub's own probe last saw — `working`,
 * `dead`, `unknown`, or `blocked` when the channel's host refused this house rather than being absent.
 */
data class LiveChannel(
    val identity: String,
    val name: String,
    val group: String,
    val groups: List<String>,
    val logo: String,
    val number: Int,
    val quality: String,
    val health: String,
    val error: String,
    val now: String,
    val next: String,
    val inLineup: Boolean,
)

/** The live wall as one profile may watch it: the channels, the lineup, and why the wall is its size. */
data class LiveWall(
    val channels: List<LiveChannel>,
    /** The profile's lineup: the channels it switched on. Empty means "everything this profile may watch". */
    val only: List<String>,
    /** The lineup's order — the order the remote walks. */
    val order: List<String>,
    val hidden: List<String>,
    val groups: List<String>,
    /** Channels the lineup is holding back from this profile, stated rather than hidden. */
    val unlisted: Int,
    val playlist: String,
    val wallError: String,
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

    /**
     * The optional add-ons this hub has installed. The navigation asks this before it draws a Games
     * or Live TV door: a tab for a mode the household never added is a door to an empty room. The
     * answer is per profile — a child's scope refuses the live row, and a Live tab that opens on
     * "not for this profile" is worse than no tab. A hub too old to answer returns null, and the
     * caller keeps its previous shape.
     */
    fun caps(profileId: String, done: (FeatureCaps?) -> Unit) {
        request("/app/v1/caps", "profile=" + enc(profileId), "GET", null, 8000) { root, status, _ ->
            val body = root
            done(
                if (status in 200..299 && body != null && body.has("live") && body.has("games")) {
                    FeatureCaps(body.optBoolean("live", false), body.optBoolean("games", false))
                } else null
            )
        }
    }

    /**
     * The live wall as this profile may watch it — the same list `/app/v1/row/live` builds, with the
     * channel manager's extra facts (numbers, health, now/next, the lineup itself). One source for the
     * wall and the zap ring is the point: a manager that offers channels the player cannot reach is how
     * "channel 214" becomes a key press that does nothing.
     */
    fun liveChannels(profileId: String, done: (wall: LiveWall?, error: String?) -> Unit) {
        request("/app/v1/live/channels", "profile=" + enc(profileId), "GET", null, 20000) { root, status, error ->
            if (root == null) { done(null, error); return@request }
            if (status == 404) { done(null, null); return@request }   // a hub too old to have this route
            if (status == 403) {
                done(null, error ?: "Live TV is not for this profile")
                return@request
            }
            val channels = ArrayList<LiveChannel>()
            root.optJSONArray("channels")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val c = arr.optJSONObject(i) ?: continue
                    channels.add(
                        LiveChannel(
                            identity = s(c, "identity", ""),
                            name = s(c, "name", ""),
                            group = s(c, "group", ""),
                            groups = stringList(c.optJSONArray("groups")),
                            logo = s(c, "logo", ""),
                            number = c.optInt("number", 0),
                            quality = s(c, "quality", ""),
                            health = s(c, "health", "unknown"),
                            error = s(c, "error", ""),
                            now = c.optJSONObject("now")?.let { s(it, "title", "") }.orEmpty(),
                            next = c.optJSONObject("next")?.let { s(it, "title", "") }.orEmpty(),
                            inLineup = c.optBoolean("inLineup", false),
                        )
                    )
                }
            }
            val lineup = root.optJSONObject("lineup")
            val status1 = root.optJSONObject("status")
            done(
                LiveWall(
                    channels = channels,
                    only = stringList(lineup?.optJSONArray("only")),
                    order = stringList(lineup?.optJSONArray("order")),
                    hidden = stringList(lineup?.optJSONArray("hidden")),
                    groups = stringList(root.optJSONArray("groups")),
                    unlisted = root.optInt("unlisted", 0),
                    playlist = s(status1, "playlist", ""),
                    wallError = s(status1, "lastError", ""),
                ),
                error,
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
            val ok = root?.optBoolean("ok") == true
            if (ok) {
                val session = root?.optString("adminSession", "")?.trim().orEmpty()
                if (session.isNotBlank()) {
                    prefs.setAdminSession(session, HubEndpoint.base(context),
                        root?.optLong("adminSessionExpiresAt", 0L) ?: 0L)
                } else {
                    // Unlocked/legacy hubs need no capability; do not retain one for a different lock.
                    prefs.clearAdminSession()
                }
            }
            done(ok, error ?: if (status == 403) "That PIN is not right" else null)
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
        request("/app/v1/settings/lock", "", "POST", body, 12000) { root, _, error ->
            if (root?.optBoolean("ok") == true) {
                val session = root.optString("adminSession", "").trim()
                if (session.isNotBlank()) {
                    prefs.setAdminSession(session, HubEndpoint.base(context),
                        root.optLong("adminSessionExpiresAt", 0L))
                } else {
                    prefs.clearAdminSession()
                }
            }
            done(root, error)
        }
    }

    /** Installed add-ons, with the same enable/remove/add calls the web settings uses. */
    fun addons(done: (addons: JSONArray?, error: String?) -> Unit) {
        request("/app/v1/addons", "", "GET", null, 12000) { root, _, error ->
            done(root?.optJSONArray("addons"), error)
        }
    }

    /** The Hub-owned, reviewed source directory. Older/standalone backends may answer an empty list. */
    fun reviewedAddons(done: (addons: JSONArray?, error: String?) -> Unit) {
        request("/app/v1/addon-registry", "", "GET", null, 12000) { root, _, error ->
            // `directory.entries` is the richer, manifest-derived directory contract.  Keep the
            // original `reviewed` field as a fallback so a TV can pair with an already-deployed
            // Hub without a coordinated update.  Neither shape contains a manifest URL.
            val entries = root?.optJSONObject("directory")?.optJSONArray("entries")
                ?: root?.optJSONArray("reviewed")
            done(entries, error)
        }
    }

    /**
     * The non-endorsed Community directory is Hub-owned. The native client accepts only its fixed
     * media-type vocabulary and an 80-character search term; it never receives provider addresses.
     */
    fun communityAddonDirectory(
        type: String,
        query: String,
        done: (directory: JSONObject?, error: String?) -> Unit,
    ) {
        val safeType = communityType(type)
        val safeQuery = query.trim().take(80)
        request(
            "/app/v1/community-addon-directory",
            "type=${enc(safeType)}&q=${enc(safeQuery)}",
            "GET",
            null,
            15000,
        ) { root, _, error -> done(root, error) }
    }

    /** Reads one opaque Community entry; the activity validates its non-endorsement contract. */
    fun communityAddonDetail(
        opaqueId: String,
        done: (entry: JSONObject?, error: String?) -> Unit,
    ) {
        val id = opaqueId.trim()
        if (id.isBlank()) {
            done(null, "That community source is unavailable")
            return
        }
        request("/app/v1/community-addon-directory/${enc(id)}", "", "GET", null, 15000) { root, _, error ->
            done(root, error)
        }
    }

    /** Community installation is opaque-ID-only and always carries the explicit acknowledgement. */
    fun installCommunityAddon(
        opaqueId: String,
        done: (root: JSONObject?, error: String?) -> Unit,
    ) {
        val id = opaqueId.trim()
        if (id.isBlank()) {
            done(null, "That community source is unavailable")
            return
        }
        request(
            "/app/v1/community-addon-directory/${enc(id)}/install",
            "",
            "POST",
            JSONObject().put("nonEndorsementAcknowledged", true),
            15000,
        ) { root, _, error -> done(root, error) }
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

    /** Install only by the Hub's reviewed registry id; a TV never supplies the upstream URL. */
    fun addReviewedAddon(id: String, done: (root: JSONObject?, error: String?) -> Unit) {
        request("/app/v1/addon-registry/" + enc(id) + "/install", "", "POST", null, 15000) { root, _, error ->
            done(root, error)
        }
    }

    /**
     * Starts an opaque, expiring browser handoff for a configuration-required reviewed source.
     * The response contains only a short code and source label — never an upstream manifest or
     * configuration field/value — so the TV cannot become a place where setup secrets appear.
     */
    fun startReviewedSourceSetup(id: String, done: (root: JSONObject?, error: String?) -> Unit) {
        request("/app/v1/addon-registry/" + enc(id) + "/setup-session", "", "POST", null, 12000) { root, _, error ->
            done(root, error)
        }
    }

    /** Community setup keeps the same acknowledgement as installation before the Hub mints a code. */
    fun startCommunitySourceSetup(id: String, done: (root: JSONObject?, error: String?) -> Unit) {
        request(
            "/app/v1/community-addon-directory/" + enc(id) + "/setup-session",
            "",
            "POST",
            JSONObject().put("nonEndorsementAcknowledged", true),
            12000,
        ) { root, _, error -> done(root, error) }
    }

    /** Polls only the code's safe receipt state after the owner has finished setup on the web. */
    fun sourceSetupStatus(code: String, done: (root: JSONObject?, error: String?) -> Unit) {
        val clean = code.trim()
        if (clean.isBlank()) {
            done(null, "That setup code is unavailable")
            return
        }
        request("/app/v1/source-setups/" + enc(clean), "", "GET", null, 10000) { root, _, error ->
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

    /**
     * The phone remote's session channel (hub/core/remote.js and core/RemoteSession.kt share this
     * contract). Registration is the only call that needs the household's hub credential; the phone
     * then proves itself with the random token alone.
     */
    fun remoteRegister(token: String, code: String, done: (root: JSONObject?, error: String?) -> Unit) {
        val body = JSONObject().put("token", token)
        if (code.isNotBlank()) body.put("code", code)
        request("/app/v1/remote/register", "", "POST", body, 10000) { root, _, error -> done(root, error) }
    }

    /** Long-poll-free event read: the TV asks after the last sequence it injected. */
    fun remoteEvents(token: String, after: Int, done: (root: JSONObject?, error: String?) -> Unit) {
        request("/app/v1/remote/events", "t=" + enc(token) + "&after=$after", "GET", null, 8000) { root, _, error ->
            done(root, error)
        }
    }

    /** End the phone session when Settings closes; it also expires on its own after 15 minutes. */
    fun remoteClose(token: String, done: (root: JSONObject?, error: String?) -> Unit) {
        request("/app/v1/remote/close", "", "POST", JSONObject().put("token", token), 8000) { root, _, error ->
            done(root, error)
        }
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
                    val why = root.optString("detail").takeIf { it.isNotBlank() }
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
                    val adminSession = prefs.adminSessionFor(base)
                    if (adminSession.isNotBlank()) {
                        conn.setRequestProperty("X-Scenix-Admin-Session", adminSession)
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
                        // A short-lived settings capability is only useful while the server accepts
                        // it. Drop an expired/revoked proof immediately so the next protected visit
                        // reopens the PIN gate instead of repeatedly sending a known-bad token.
                        if (status == 403 && root?.optBoolean("sessionRequired", false) == true) {
                            prefs.clearAdminSession()
                        }
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

        fun communityType(value: String): String = when (value.trim().lowercase()) {
            "movie", "series", "channel", "other" -> value.trim().lowercase()
            else -> "movie"
        }
    }
}
