package com.scenix.tv.core

import android.app.Application
import android.util.Log
import android.widget.FrameLayout

/**
 * Everything a plugin may reach: theme, prefs, image cache, registries, the hub client, and the
 * active profile. Screens are built by [ScreenPlugin.render] against a [ScreenHost].
 *
 * Plugins talk to these two types and nothing else, which is what keeps a new plugin from having
 * to change an existing one.
 */
class AppContext(
    val app: Application,
    val theme: Theme,
    val prefs: Prefs,
    val images: ImageLoader,
    val registries: Registries,
    val screenW: Int,
    val screenH: Int,
) {
    /** Replaced by MainActivity when `--es fixture …` asks for the offline asset client. */
    var hub: HubClient = HttpHub(app, prefs)

    /** The signed-in profile for this session; null on the profile picker. */
    var profile: Profile? = null

    /** Last home page the app rendered, so Settings can list row ids without another round-trip. */
    var lastHome: List<Row> = emptyList()

    /** True while HubWatch has fallen back to the bundled fixtures. Screens show a small chip. */
    var offline: Boolean = false

    /**
     * Source capability. The players registry IS the capability list — a source whose `kind` has no
     * plugin (or whose plugin says this TV cannot take it) is listed to the viewer but never opened by
     * a single tap on Play. RiskOrder already puts adaptive first and torrent last, so choosing is a
     * filter, not a sort.
     */
    /** The plugin that would open this source; null when this TV has no plugin for that kind. */
    fun pluginFor(source: Source): PlayerPlugin? = registries.players.get(source.kind)

    /** Whether the UI may offer this source at all — a plugin exists and says it can open it here. */
    fun canOpen(source: Source): Boolean = pluginFor(source)?.openable(this, source) == true

    /** True when tapping this source leaves the app (a companion engine) instead of playing in place. */
    fun opensExternally(source: Source): Boolean = pluginFor(source)?.external() == true

    /**
     * The hub's ranking, best first. It already arrives that way; sorting by `score` is only a
     * defence against a hub that forgets, and it is stable, so equal scores keep the hub's order.
     */
    fun ranked(item: Item): List<Source> = item.sources.sortedByDescending { it.score }

    /**
     * What a single tap on Play opens: the top of the ranking that this TV can play *in place*.
     *
     * A torrent ranked first stays visible in "Play from" but is not what Play reaches for — this set
     * has no engine for it. If every source is a hand-off, the first one is returned anyway so the
     * player can explain itself instead of doing nothing.
     */
    fun playableSource(item: Item): Source? =
        ranked(item).firstOrNull { canOpen(it) && !opensExternally(it) }
            ?: ranked(item).firstOrNull { canOpen(it) }
            ?: item.sources.firstOrNull()

    /** Safe gutter every screen leaves on the left/right (theme: 64 px at 1080p). */
    val gutterPx: Int get() = theme.gutter

    /** Apply every RulePlugin, in order, to rows straight off the hub. */
    fun applyRules(rows: List<Row>): List<Row> {
        val p = profile ?: Profile(id = "", name = "")
        val rctx = RuleCtx(p, this)
        var out = rows
        for (rule in registries.rules.all()) {
            val next = ArrayList<Row>(out.size)
            for (row in out) {
                val r = try {
                    val items = ArrayList<Item>(row.items.size)
                    for (item in row.items) {
                        val mapped = rule.applyItem(item, rctx)
                        if (mapped != null) items.add(mapped)
                    }
                    rule.applyRow(row.copy(items = items), rctx)
                } catch (t: Throwable) {
                    Log.w(TAG, "rule ${rule.id} threw, row kept as-is", t)
                    row
                }
                if (r != null) next.add(r)
            }
            out = next
        }
        return out
    }

    private companion object { const val TAG = "ScenixAppCtx" }
}

/**
 * The navigation surface a screen is given. A screen never reaches into the Activity; it asks to
 * be moved, and the host decides how (view stack in MainActivity).
 */
interface ScreenHost {
    val app: AppContext

    /** The container this screen must fill. Already cleared. */
    fun container(): FrameLayout

    /** Push another screen (nav, card click, "explore all"). */
    fun show(screenId: String, arg: String? = null)

    /** The argument the current screen was opened with ("row" id, search query, …). */
    fun arg(): String?

    /** Open the Detail screen for one identity. */
    fun openDetail(identity: String, seed: Item? = null)

    /** Open the Player for one item, optionally pinned to a specific source. */
    fun play(item: Item, source: Source?)

    /** Detail payload for the screen stack. */
    fun seedItem(identity: String): Item?

    /** The item + source the player was asked for, set by [play]. Null on every other screen. */
    fun playRequest(): Pair<Item, Source?>? = null

    /** Replace the whole stack with the profile picker (PIN gate / "switch profile"). */
    fun requireProfilePicker(reason: String? = null)

    fun toast(msg: String)

    /** Screens currently registered in the nav, for this profile. */
    fun navScreens(): List<ScreenPlugin> = app.registries.navFor(app.profile)

    /** The id of the screen on top, used by the nav bar to mark the current one. */
    fun currentScreenId(): String

    /** Set the active profile (called by the profile picker after any PIN gate). */
    fun enterProfile(profile: Profile)
}
