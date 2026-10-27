package com.scenix.tv.plugins.nav

import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import com.scenix.tv.R
import com.scenix.tv.core.Addon
import com.scenix.tv.core.AppContext
import com.scenix.tv.core.Focus
import com.scenix.tv.core.FixtureHub
import com.scenix.tv.core.Format
import com.scenix.tv.core.HttpHub
import com.scenix.tv.core.HubDiscovery
import com.scenix.tv.core.HubError
import com.scenix.tv.core.HubPrefs
import com.scenix.tv.core.LocalHub
import com.scenix.tv.core.NavBar
import com.scenix.tv.core.PinPad
import com.scenix.tv.core.Prefs
import com.scenix.tv.core.ScreenHost
import com.scenix.tv.core.ScreenPlugin
import com.scenix.tv.core.Views
import org.json.JSONArray
import org.json.JSONObject

/**
 * Settings-lite: the handful of switches that change how the app behaves on this TV, plus a dump of
 * the live plugin registry so it is obvious which plugin is responsible for what you are looking at.
 *
 * Nothing here is a content setting. Row visibility, the PIN and My List are stored on the TV
 * (SharedPreferences); the hub address and fixture mode are per-device; player ceilings come from
 * `res/values/config.xml` and `assets/theme.json`, which is why they are shown read-only here.
 */
object SettingsScreen : ScreenPlugin {
    override val id get() = "settings"
    override val title get() = "Settings"
    override val route get() = "settings"
    override val showInNav = false
    override val order = 500

    override fun render(host: ScreenHost) {
        val ctx = host.app
        val theme = ctx.theme
        val container = host.container()

        val root = LinearLayout(ctx.app)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(theme.bg)
        container.addView(
            root,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        root.addView(NavBar(ctx, host).view(id))

        val scroll = ScrollView(ctx.app)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val col = LinearLayout(ctx.app)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(theme.gutter, theme.px(26f), theme.gutter, theme.px(60f))
        scroll.addView(col)

        section(ctx, col, "Connection")
        if (ctx.prefs.standalone()) {
            infoRow(ctx, col, "Backend", "this TV, on its own \u2014 no hub to reach")
            actionRow(ctx, col, "Stream from a hub instead", "the hub\u2019s library replaces this one", "Choose a hub") {
                ctx.prefs.setBackendChoice("hub")
                ctx.prefs.setStandalone(false)
                ctx.hub = HttpHub(ctx.app, ctx.prefs)
                ctx.capabilities = null
                ctx.watch?.rechoose()
                host.show("pairing")
            }
        } else {
            textRow(ctx, col, "Hub address", ctx.prefs.hubUrl(ctx.app.getString(R.string.hub_url)), editable = true) { value ->
                // A typed address goes through the same door as an announced one: `host:8788` written on
                // the remote is as good as a full URL.
                val url = HubDiscovery.normalise(value) ?: value.trim()
                ctx.prefs.setHubUrl(url)
                // Typing an address means "show me the real library", so it drops the offline-preview pin as
                // well. Without that the rows on screen keep coming from the bundled samples and the new
                // address looks like it did nothing at all.
                ctx.prefs.setFixture("")
                ctx.prefs.setBackendChoice("hub")
                ctx.prefs.setStandalone(false)
                ctx.hub = HttpHub(ctx.app, ctx.prefs)
                ctx.capabilities = null
                ctx.watch?.rechoose()
                host.toast("Hub set to $url")
                host.refresh()
            }
            val fixture = ctx.prefs.fixture()
            actionRow(
                ctx, col, "Offline preview",
                // The bundled sample library is a development aid, not a mode a household should sit in:
                // it has last month's rows and no live artwork, so the state is spelled out here.
                if (fixture.isNullOrBlank()) "off \u2014 streaming from the hub" else "on \u2014 bundled sample library",
                if (fixture.isNullOrBlank()) "Use the bundled samples (no network)" else "Go back to the hub",
            ) {
                val turnOn = fixture.isNullOrBlank()
                ctx.prefs.setFixture(if (turnOn) "fixtures/home.json" else "")
                ctx.hub = if (turnOn) FixtureHub(ctx.app, "fixtures/home.json") else HttpHub(ctx.app, ctx.prefs)
                host.toast(if (turnOn) "Bundled samples on" else "Streaming from the hub")
                host.show("home")
            }
            hubSearch(ctx, col, host)
        }
        textRow(ctx, col, "Data client", ctx.hub.label, editable = false, onChange = {})

        section(ctx, col, "Interface")
        // Which front end this TV draws is the owner's decision, not something a build decides for them:
        // the page and these screens speak to the same backend, and a household may reasonably prefer
        // either. The choice belongs to the device, so it is stored here rather than on the hub.
        if (ctx.prefs.shellWanted()) {
            infoRow(ctx, col, "Front end", "the web interface \u2014 the same screens a browser or phone shows")
            actionRow(ctx, col, "Draw the built-in screens instead",
                "this app\u2019s own browse, rows and details", "Switch") {
                ctx.prefs.setInterfaceMode(Prefs.NATIVE)
                host.toast("Using this TV\u2019s own screens")
                host.refresh()
            }
        } else {
            infoRow(ctx, col, "Front end", "this TV\u2019s built-in screens")
            actionRow(ctx, col, "Use the web interface instead",
                "the page every other device shows, over this TV\u2019s player", "Switch") {
                ctx.prefs.setInterfaceMode(Prefs.SHELL)
                host.toast("The web interface opens next time Scenix starts")
                host.refresh()
            }
        }

        section(ctx, col, "Player ceilings (from res/values/config.xml)")
        infoRow(ctx, col, "Max video size", "${theme.playerMaxWidth}x${theme.playerMaxHeight}")
        infoRow(ctx, col, "Max bitrate", Format.bitrate(theme.playerMaxBitrate))
        infoRow(ctx, col, "Back buffer (30 s rewind stays in RAM)", Format.seconds(theme.playerBackBufferMs.toLong()))
        infoRow(ctx, col, "Forward buffer", Format.seconds(theme.playerForwardBufferMs.toLong()))
        infoRow(ctx, col, "Seek steps", "\u2212${Format.seconds(theme.seekBackMs.toLong())} / +${Format.seconds(theme.seekForwardMs.toLong())}")

        section(ctx, col, "This profile")
        val profile = ctx.profile
        actionRow(ctx, col, "Profile", profile?.name ?: "none", "Switch profile") {
            host.requireProfilePicker("switch")
        }
        actionRow(
            ctx, col, "Parental PIN",
            if (profile != null && ctx.prefs.hasPin(profile.id)) "set on this TV" else "not set",
            "Change PIN",
        ) {
            val p = profile ?: return@actionRow
            var overlay: View? = null
            val pad = PinPad(
                ctx = ctx,
                title = if (ctx.prefs.hasPin(p.id)) "New PIN for ${p.name}" else "Set a PIN for ${p.name}",
                subtitle = "Stored hashed on this TV. Four digits. Cancel leaves it as it was.",
                onDone = { entered ->
                    ctx.prefs.setPin(p.id, entered)
                    overlay?.let { PinPad.close(container, it) }
                    host.toast("PIN saved for ${p.name}")
                },
                onCancel = {
                    overlay?.let { PinPad.close(container, it) }
                },
            )
            overlay = pad.view()
            container.addView(overlay)
        }

        section(ctx, col, "Hide rows on this profile")
        val rows = ctx.lastHome
        if (rows.isEmpty()) {
            infoRow(ctx, col, "Row visibility", "Open Home first, then come back here.")
        }
        for (row in rows) {
            toggleRow(ctx, col, row.title.ifBlank { row.id }, row.id)
        }

        if (ctx.prefs.standalone()) localAddonsSection(ctx, col, host) else addonsSection(ctx, col)
        prefsSection(ctx, host, col)

        section(ctx, col, "Loaded plugins (PluginCatalog.kt)")
        infoRow(ctx, col, "Cards", ctx.registries.cards.keys().joinToString(", "))
        infoRow(ctx, col, "Rows", ctx.registries.rows.keys().joinToString(", "))
        infoRow(ctx, col, "Rules", ctx.registries.rules.keys().joinToString(", "))
        infoRow(ctx, col, "Players", ctx.registries.players.keys().joinToString(", "))
        infoRow(ctx, col, "Screens", ctx.registries.screens.keys().joinToString(", "))
        infoRow(ctx, col, "Image cache", ctx.images.stats())
    }

    /**
     * Hubs answering a search on this network. A hub announces itself (design/deployment-modes.md Rule
     * 4), so nobody has to know an address: the list below is what the last search remembered, each
     * entry under the name its owner gave it rather than a host.
     */
    private fun hubSearch(ctx: AppContext, col: LinearLayout, host: ScreenHost) {
        actionRow(
            ctx, col, "Find hubs on this network",
            if (HubDiscovery.isScanning()) "searching\u2026" else "", "Search",
        ) {
            if (HubDiscovery.isScanning()) return@actionRow
            HubDiscovery.scan(
                ctx.app,
                ctx.app.getString(R.string.ssdp_search_target),
                ctx.app.resources.getInteger(R.integer.ssdp_search_ms),
            ) { found ->
                val known = LinkedHashMap(ctx.prefs.discoveredHubs().associateBy { it.url })
                for (f in found) known[f.url] = f
                ctx.prefs.setDiscoveredHubs(ArrayList(known.values))
                host.toast(if (found.isEmpty()) "No hub answered" else "Found ${found.size} hub(s)")
                if (host.currentScreenId() == id) host.refresh()
            }
        }
        for (found in ctx.prefs.discoveredHubs()) {
            actionRow(ctx, col, found.display(), found.url, "Use") {
                ctx.prefs.setHubUrl(found.url)
                ctx.prefs.setHubName(found.url, found.name)
                ctx.prefs.setBackendChoice("hub")
                ctx.prefs.setStandalone(false)
                ctx.prefs.setFixture("")
                ctx.hub = HttpHub(ctx.app, ctx.prefs)
                ctx.capabilities = null
                ctx.watch?.rechoose()
                host.toast("Streaming from ${found.display()}")
                host.refresh()
            }
        }
    }

    /**
     * Standalone mode's addons: this TV fetches its own content, so the list is edited here instead of
     * on a hub. Only the manifest URL is stored — what an addon offers is whatever its own manifest
     * says, and a build that shipped a URL would be a build that shipped someone else's household.
     */
    private fun localAddonsSection(ctx: AppContext, col: LinearLayout, host: ScreenHost) {
        val local = LocalHub.get(ctx.app)
        section(ctx, col, "Addons (fetched by this TV)")
        val stored = local.settings().optJSONArray("addons") ?: JSONArray()
        if (stored.length() == 0) {
            infoRow(ctx, col, "No addons yet", "Add an addon URL below and this TV reads that addon\u2019s own catalogues.")
        }
        for (i in 0 until stored.length()) {
            val entry = stored.optJSONObject(i) ?: continue
            val url = entry.optString("manifest", entry.optString("url", ""))
            if (url.isBlank()) continue
            val name = entry.optString("name", "").ifBlank {
                runCatching { java.net.URI(url).host }.getOrNull() ?: url
            }
            actionRow(ctx, col, name, url, "Remove") {
                val kept = JSONArray()
                for (k in 0 until stored.length()) {
                    val e = stored.optJSONObject(k) ?: continue
                    if (e.optString("manifest", e.optString("url", "")) != url) kept.put(e)
                }
                local.setSettings(JSONObject().put("addons", kept))
                host.toast("Removed $name")
                host.refresh()
            }
        }
        textRow(ctx, col, "Add an addon (manifest URL)", "", editable = true) { value ->
            val url = value.trim()
            if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) {
                host.toast("An addon URL starts with http:// or https://")
                return@textRow
            }
            val arr = local.settings().optJSONArray("addons") ?: JSONArray()
            arr.put(JSONObject().put("manifest", url))
            local.setSettings(JSONObject().put("addons", arr))
            local.refreshAddons { n, err ->
                val count = n ?: 0
                host.toast(
                    if (err != null) "Saved, but the addon did not answer ($err)"
                    else "Saved \u2014 $count addon(s) answering",
                )
                if (host.currentScreenId() == id) host.refresh()
            }
        }
        actionRow(ctx, col, "Read the addons again", "", "Fetch") {
            local.refreshAddons { n, err ->
                val count = n ?: 0
                host.toast(if (err != null) "The addons did not answer ($err)" else "$count addon(s) answering")
                if (host.currentScreenId() == id) host.refresh()
            }
        }
        actionRow(ctx, col, "Cached addon responses", "on this TV", "Clear") {
            local.clearAddonCache()
            host.toast("Cache cleared")
        }
    }

    // ---- small builders --------------------------------------------------------------------

    /**
     * What the hub pulls its content from: `GET /app/v1/addons`, falling back to the `addons` section
     * of the manifest on a hub that has not got that endpoint yet. Read-only — addons are managed on
     * the hub, never on the TV. A hub that reports nothing leaves one quiet line, not a broken screen.
     */
    private fun addonsSection(ctx: AppContext, col: LinearLayout) {
        val block = LinearLayout(ctx.app)
        block.orientation = LinearLayout.VERTICAL
        col.addView(block)
        section(ctx, block, "Addons")
        val rows = LinearLayout(ctx.app)
        rows.orientation = LinearLayout.VERTICAL
        block.addView(rows)
        infoRow(ctx, rows, "Addons", "checking this hub\u2026")

        ctx.hub.addons { list, _ ->
            BrowseHelper.runOnUiThread {
                if (block.parent == null) return@runOnUiThread
                val items = list ?: emptyList()
                if (items.isNotEmpty()) {
                    rows.removeAllViews()
                    for (a in items) addonRow(ctx, rows, a)
                    return@runOnUiThread
                }
                // No addons endpoint (or an empty one): the manifest may still carry the section.
                ctx.hub.manifest { m, error ->
                    BrowseHelper.runOnUiThread {
                        if (block.parent != null) {
                            rows.removeAllViews()
                            val fromManifest = m?.addons ?: emptyList()
                            when {
                                fromManifest.isNotEmpty() -> for (a in fromManifest) addonRow(ctx, rows, a)
                                error != null -> infoRow(ctx, rows, "Addons", "not reported by this hub")
                                else -> infoRow(ctx, rows, "Addons", "none installed")
                            }
                        }
                    }
                }
            }
        }
    }

    private fun addonRow(ctx: AppContext, col: LinearLayout, a: Addon) {
        val theme = ctx.theme
        val origin = when (a.origin.lowercase()) {
            "native", "built-in", "builtin", "internal" -> "built in"
            "user", "installed", "user-installed" -> "installed"
            else -> a.origin
        }
        val label = if (origin.isBlank()) a.name else "${a.name} \u00B7 $origin"
        val facts = ArrayList<String>()
        if (a.kinds.isNotEmpty()) facts.add(a.kinds.joinToString("/"))
        if (a.count > 0) facts.add("${a.count} items")
        if (!a.enabled) facts.add("disabled")
        infoRow(ctx, col, label, facts.joinToString(" \u00B7 ").ifBlank { "\u2014" })
        if (a.lastError.isNotBlank()) {
            infoRow(ctx, col, "last error", a.lastError)
                .setTextColor(theme.color("warn", "#F2C33A"))
        }
    }

    /**
     * Per-profile playback preference, stored on the hub (`GET`/`POST /app/v1/prefs`) rather than on
     * this TV: the ranking is the hub's job, so what the owner cares about has to travel with the
     * profile. Every change is written back immediately, and the sentence under the rows says in plain
     * words how a title would be picked now — the same reason the hub puts in `why[]`.
     */
    private fun prefsSection(ctx: AppContext, host: ScreenHost, col: LinearLayout) {
        val block = LinearLayout(ctx.app)
        block.orientation = LinearLayout.VERTICAL
        col.addView(
            block,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        section(ctx, block, "Playback preference (stored ${if (ctx.prefs.standalone()) "on this TV" else "on the hub"})")
        val body = LinearLayout(ctx.app)
        body.orientation = LinearLayout.VERTICAL
        block.addView(
            body,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        infoRow(ctx, body, "Preference", "asking the hub\u2026")

        ctx.hub.prefs(ctx.profile?.id) { loaded, error ->
            BrowseHelper.runOnUiThread {
                if (block.parent == null) return@runOnUiThread
                body.removeAllViews()
                if (loaded == null) {
                    infoRow(
                        ctx, body, "Playback preference",
                        if (HubError.forbidden(error)) "this profile may not change it"
                        else "not stored by this hub \u2014 the player uses its own ceilings",
                    )
                    return@runOnUiThread
                }
                drawPrefs(ctx, host, body, loaded)
            }
        }
    }

    private fun drawPrefs(ctx: AppContext, host: ScreenHost, body: LinearLayout, prefs: HubPrefs) {
        adoptPrefs(ctx, prefs)
        // Nothing is preselected: which languages this household listens in is not a fact about the app,
        // and an unset order means "whatever the source offers first".
        val audio = prefs.audioLanguages.ifEmpty { ctx.profile?.audioLanguages ?: emptyList() }

        textRow(ctx, body, "Audio language order (codes, in order)", audio.joinToString(", "), editable = true) { value ->
            savePrefs(ctx, host, body, prefs.copy(audioLanguages = codes(value)))
        }
        actionRow(ctx, body, "Quality preference", qualityLabel(prefs.quality), "Change") {
            savePrefs(ctx, host, body, prefs.copy(quality = nextQuality(prefs.quality)))
        }
        actionRow(ctx, body, "Max file size",
            if (prefs.maxFileSizeBytes <= 0L) "no cap" else Format.size(prefs.maxFileSizeBytes), "Change") {
            savePrefs(ctx, host, body, prefs.copy(maxFileSizeBytes = nextSize(prefs.maxFileSizeBytes)))
        }
        actionRow(ctx, body, "Max bitrate",
            if (prefs.maxBitrateBps <= 0) "no cap" else Format.bitrate(prefs.maxBitrateBps), "Change") {
            savePrefs(ctx, host, body, prefs.copy(maxBitrateBps = nextBitrate(prefs.maxBitrateBps)))
        }

        // The dark-red tier. Adults only, one line, and worded so that a screen left on shows a
        // settings entry rather than a category of content. Off is the hub's default too, so the
        // content is absent from search, rows and history until this line is touched.
        if (ctx.profile != null && !ctx.profile!!.isKids) {
            actionRow(
                ctx, body, "Late night mode",
                if (prefs.lateNight) "on" else "off",
                if (prefs.lateNight) "Turn off" else "Turn on",
            ) {
                savePrefs(ctx, host, body, prefs.copy(lateNight = !prefs.lateNight))
            }
        }

        val why = infoRow(ctx, body, "How a title would be chosen", whySentence(prefs))
        why.setTextColor(ctx.theme.color("kids", "#F2C33A"))
        sampleWhy(ctx, body)
    }

    /** One real answer from the hub, so the sentence above is not just our own arithmetic. */
    private fun sampleWhy(ctx: AppContext, body: LinearLayout) {
        val sample = ctx.lastHome.asSequence()
            .flatMap { it.items.asSequence() }
            .firstOrNull { it.sources.isNotEmpty() && !it.isLive() } ?: return
        val src = ctx.ranked(sample).firstOrNull() ?: return
        ctx.hub.play(sample.identity, src.provider, ctx.profile?.id, sample.type) { target, _ ->
            BrowseHelper.runOnUiThread {
                if (body.parent == null) return@runOnUiThread
                val lines = target?.why ?: emptyList()
                if (lines.isEmpty()) return@runOnUiThread
                infoRow(ctx, body, "For \u201c${sample.title}\u201d the hub says", lines.joinToString(" \u00B7 "))
            }
        }
    }

    /** Write the preference back and repaint the block, so the sentence matches what was saved. */
    private fun savePrefs(ctx: AppContext, host: ScreenHost, body: LinearLayout, prefs: HubPrefs) {
        ctx.hub.setPrefs(prefs, ctx.profile?.id) { back, error ->
            BrowseHelper.runOnUiThread {
                if (body.parent == null) return@runOnUiThread
                body.removeAllViews()
                if (back == null) {
                    infoRow(ctx, body, "Playback preference", "the hub did not save it (${error ?: "no answer"})")
                    return@runOnUiThread
                }
                drawPrefs(ctx, host, body, back)
            }
        }
    }

    /** The hub's preference for this profile also drives the player on this TV. */
    private fun adoptPrefs(ctx: AppContext, prefs: HubPrefs) {
        val p = ctx.profile ?: return
        ctx.profile = p.copy(
            audioLanguages = prefs.audioLanguages.ifEmpty { p.audioLanguages },
            subtitleLanguages = prefs.subtitleLanguages.ifEmpty { p.subtitleLanguages },
        )
    }

    private val QUALITIES = arrayOf("", "480", "720", "1080", "2160")
    private val SIZES = longArrayOf(0, 500_000_000L, 1_000_000_000L, 2_000_000_000L, 4_000_000_000L)
    private val BITRATES = intArrayOf(0, 2_000_000, 4_000_000, 6_000_000, 10_000_000)

    private fun nextQuality(cur: String): String =
        QUALITIES[(QUALITIES.indexOf(cur.lowercase()) + 1 + QUALITIES.size) % QUALITIES.size]

    private fun nextSize(cur: Long): Long =
        SIZES[(SIZES.indexOf(cur) + 1 + SIZES.size) % SIZES.size]

    private fun nextBitrate(cur: Int): Int =
        BITRATES[(BITRATES.indexOf(cur) + 1 + BITRATES.size) % BITRATES.size]

    /** Typed language codes, kept in the order typed: two codes separated by a comma become a list of two. */
    private fun codes(value: String): List<String> =
        value.split(',', ';', ' ').map { it.trim().lowercase() }.filter { it.isNotEmpty() }

    private fun qualityLabel(q: String): String = when (q.lowercase()) {
        "", "auto" -> "auto"
        "2160", "4k" -> "4K"
        "1080" -> "1080p"
        "720" -> "720p"
        "480" -> "480p"
        else -> q
    }

    private fun whySentence(p: HubPrefs): String {
        val parts = ArrayList<String>()
        if (p.audioLanguages.isNotEmpty()) parts.add("prefers " + p.audioLanguages.joinToString(" then ") + " audio")
        if (p.subtitleLanguages.isNotEmpty()) parts.add("subtitles in " + p.subtitleLanguages.joinToString("/"))
        if (p.quality.isNotBlank()) parts.add("caps at " + qualityLabel(p.quality))
        if (p.maxFileSizeBytes > 0L) parts.add("refuses files over " + Format.size(p.maxFileSizeBytes))
        if (p.maxBitrateBps > 0) parts.add("refuses streams over " + Format.bitrate(p.maxBitrateBps))
        return if (parts.isEmpty()) "hub defaults \u2014 the best-ranked source wins" else parts.joinToString(" \u00B7 ")
    }

    private fun section(ctx: AppContext, col: LinearLayout, label: String) {
        val theme = ctx.theme
        val tv = Views.text(ctx, "rowTitle", 26f, theme.ink, label, bold = true)
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = theme.px(34f)
        lp.bottomMargin = theme.px(12f)
        col.addView(tv, lp)
    }

    private fun infoRow(ctx: AppContext, col: LinearLayout, label: String, value: String): android.widget.TextView {
        val theme = ctx.theme
        val row = LinearLayout(ctx.app)
        row.orientation = LinearLayout.HORIZONTAL
        row.setPadding(theme.px(24f), theme.px(18f), theme.px(24f), theme.px(18f))
        row.background = Views.rounded(theme.surface, theme.radius("listItem", 6f))
        val a = Views.text(ctx, "li", 23f, theme.ink2, label)
        row.addView(a, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val b = Views.text(ctx, "li", 23f, theme.ink, value, maxLines = 3)
        row.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        col.addView(row, rowLp(ctx))
        return b
    }

    private fun textRow(
        ctx: AppContext,
        col: LinearLayout,
        label: String,
        value: String,
        editable: Boolean,
        onChange: (String) -> Unit,
    ) {
        val theme = ctx.theme
        val row = LinearLayout(ctx.app)
        row.orientation = LinearLayout.VERTICAL
        row.setPadding(theme.px(24f), theme.px(18f), theme.px(24f), theme.px(18f))
        row.background = Views.rounded(theme.surface, theme.radius("listItem", 6f))
        col.addView(row, rowLp(ctx))

        row.addView(Views.text(ctx, "li", 22f, theme.ink2, label), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val field = EditText(ctx.app)
        field.setText(value)
        field.setTextColor(theme.ink)
        field.setHintTextColor(theme.ink3)
        field.setTextSize(TypedValue.COMPLEX_UNIT_PX, theme.type("li", 24f))
        field.isEnabled = editable
        field.isFocusable = editable
        field.background = Views.rounded(theme.surface2, theme.radius("field", 6f), theme.px(1f), theme.line)
        field.setPadding(theme.px(16f), theme.px(12f), theme.px(16f), theme.px(12f))
        val flp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        flp.topMargin = theme.px(10f)
        row.addView(field, flp)

        if (!editable) return
        var current = value
        field.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { current = s?.toString() ?: "" }
        })
        val save = Views.text(ctx, "button", 22f, theme.ink, "Save", bold = true, gravity = Gravity.CENTER)
        save.setPadding(theme.px(22f), theme.px(12f), theme.px(22f), theme.px(12f))
        save.background = Views.rounded(theme.brand, theme.radius("button", 6f))
        save.isFocusable = true
        save.setOnClickListener { onChange(current) }
        Focus.attach(save, save, ctx, theme.radius("button", 6f), scale = false)
        val slp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        slp.topMargin = theme.px(12f)
        row.addView(save, slp)
    }

    private fun actionRow(
        ctx: AppContext,
        col: LinearLayout,
        label: String,
        value: String,
        action: String,
        onClick: () -> Unit,
    ) {
        val theme = ctx.theme
        val row = LinearLayout(ctx.app)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(theme.px(24f), theme.px(18f), theme.px(24f), theme.px(18f))
        row.background = Views.rounded(theme.surface, theme.radius("listItem", 6f))
        row.isFocusable = true
        row.isClickable = true

        row.addView(Views.text(ctx, "li", 23f, theme.ink, label, bold = true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(Views.text(ctx, "liMeta", 21f, theme.ink2, value), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val go = Views.text(ctx, "li", 21f, theme.brand, action, bold = true)
        val glp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        glp.marginStart = theme.px(24f)
        row.addView(go, glp)

        row.setOnClickListener { onClick() }
        row.setOnFocusChangeListener { v, has ->
            v.setBackgroundColor(if (has) theme.color("surfHover", "#2F2F2F") else theme.surface)
        }
        col.addView(row, rowLp(ctx))
    }

    private fun toggleRow(
        ctx: AppContext,
        col: LinearLayout,
        label: String,
        rowId: String,
    ) {
        val theme = ctx.theme
        val profileId = ctx.profile?.id ?: ""
        val row = LinearLayout(ctx.app)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(theme.px(24f), theme.px(16f), theme.px(24f), theme.px(16f))
        row.background = Views.rounded(theme.surface, theme.radius("listItem", 6f))
        row.isFocusable = true
        row.isClickable = true

        row.addView(Views.text(ctx, "li", 23f, theme.ink, label), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val hidden0 = ctx.prefs.hiddenRows(profileId).contains(rowId)
        val state = Views.text(ctx, "li", 21f, if (hidden0) theme.brand else theme.ink3, if (hidden0) "hidden" else "shown")
        row.addView(state, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        row.setOnClickListener {
            val nowHidden = ctx.prefs.toggleHiddenRow(profileId, rowId)
            state.text = if (nowHidden) "hidden" else "shown"
            state.setTextColor(if (nowHidden) ctx.theme.brand else ctx.theme.ink3)
        }
        row.setOnFocusChangeListener { v, has ->
            v.setBackgroundColor(if (has) theme.color("surfHover", "#2F2F2F") else theme.surface)
        }
        col.addView(row, rowLp(ctx))
    }

    private fun rowLp(ctx: AppContext): LinearLayout.LayoutParams {
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = ctx.theme.px(10f)
        return lp
    }
}
