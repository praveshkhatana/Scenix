package com.scenix.tv.plugins.nav

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.recyclerview.widget.RecyclerView
import com.scenix.tv.core.AppContext
import com.scenix.tv.core.Focus
import com.scenix.tv.core.HubError
import com.scenix.tv.core.Item
import com.scenix.tv.core.NavBar
import com.scenix.tv.core.Preview
import com.scenix.tv.core.Row
import com.scenix.tv.core.ScreenHost
import com.scenix.tv.core.ScreenPlugin
import com.scenix.tv.core.Views
import com.scenix.tv.plugins.cards.Billboard
import com.scenix.tv.plugins.rows.RowAdapter
import com.scenix.tv.plugins.rows.statusRow

/**
 * Home: a floating nav over one scrolling list of the hub's rows.
 *
 * Three things Home does that the other screens do not, all of them data-driven rather than
 * content rules:
 *
 *  - the billboard. A hub that sends a row whose `card` is `hero` gets it rendered full-bleed, by the
 *    same `hero` card plugin a hero card inside the list would use, so the two can never look
 *    different. A hub that sends no hero row — which is what the live hub does today — is not allowed
 *    to look like a shelf that lost its header, so the billboard is synthesised from the first row
 *    that has landscape artwork: a backdrop where the hub has one, the poster where it does not. The
 *    cards in the list are untouched either way; the billboard is the header, not a stolen card.
 *  - the chip strip under it is the genres the hub actually sent, most frequent first, so a new
 *    genre shows up the day the hub starts tagging it.
 *  - the nav is not a row of the list. It floats on top of it, transparent while the billboard fills
 *    the head of the screen and fading to the page colour once shelves are scrolling underneath,
 *    because a strip that is part of the list cannot do that.
 */
object HomeScreen : ScreenPlugin {
    override val id get() = "home"
    override val title get() = "Home"
    override val route get() = ""
    override val order = 10

    private var billboard: Billboard? = null

    override fun render(host: ScreenHost) {
        val ctx = host.app
        val theme = ctx.theme
        val container = host.container()

        val root = FrameLayout(ctx.app)
        root.clipChildren = false
        root.setBackgroundColor(theme.bg)
        container.addView(
            root,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )

        releaseBillboard()
        val (rv, adapter) = RowAdapter.newRecyclerView(ctx, host)
        // The list owns the whole screen and starts under the nav; the nav draws over its top, so the
        // shelves slide underneath it instead of piling up against a hard strip.
        rv.setPadding(0, theme.navHeight, 0, 0)
        rv.clipToPadding = false
        root.addView(rv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val nav = NavBar(ctx, host).view(id)
        root.addView(nav)
        nav.translationZ = theme.px(40f).toFloat()

        rv.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                NavBar.tint(nav, ctx, recyclerView.computeVerticalScrollOffset())
            }
        })

        adapter.submit(listOf(statusRow(ctx, "Loading\u2026") to false), emptyList())

        ctx.hub.home(ctx.profile?.id) { page, error ->
            BrowseHelper.runOnUiThread {
                if (container.indexOfChild(root) < 0) return@runOnUiThread
                if (error != null && page == null) {
                    val msg = if (HubError.forbidden(error)) {
                        BrowseHelper.hiddenMessage(ctx)
                    } else {
                        BrowseHelper.offlineMessage(ctx, error)
                    }
                    adapter.submit(listOf(statusRow(ctx, msg) to false), emptyList())
                    return@runOnUiThread
                }
                val incoming = page ?: emptyList()
                val rows = ctx.applyRules(incoming)
                ctx.lastHome = incoming
                // A row the hub answered with nothing gets no header: a shelf with no cards under it
                // ("Continue watching" before anyone has watched anything) reads as broken, and Scenix
                // does not print it either.
                val heroes = rows.filter { it.card == "hero" && it.items.isNotEmpty() }
                val rest = rows.filter { it.card != "hero" && it.items.isNotEmpty() }

                val headers = ArrayList<Pair<View, Boolean>>()
                if (ctx.offline) headers.add(BrowseHelper.offlineChip(ctx) to false)
                val spotlight = spotlight(heroes, rest, ctx)
                if (spotlight.isNotEmpty()) headers.add(billboardHeader(ctx, host, spotlight, rest) to true)
                if (rest.isEmpty() && spotlight.isEmpty()) {
                    headers.add(statusRow(ctx, BrowseHelper.emptyMessage(ctx)) to false)
                }
                adapter.submit(headers, rest)

                // Scenix: when the screen opens the viewer is already standing on the first card of
                // the first shelf, so one press of OK opens something and Up reaches the billboard.
                // Only when nothing has focus yet — a refresh must never yank focus away from wherever
                // the viewer is standing.
                if (rv.findFocus() == null) {
                    rv.post { Focus.firstShelfCard(rv)?.requestFocus() }
                }
            }
        }
    }

    /** Leaving Home stops the carousel and gives the hero's decoder back to the TV. */
    override fun onLeave(host: ScreenHost) {
        releaseBillboard()
        Preview.release()
    }

    private fun releaseBillboard() {
        billboard?.release()
        billboard = null
    }

    /**
     * The titles the billboard rotates through.
     *
     * A `hero` row wins when the hub sends one. Otherwise it is the first row with landscape artwork,
     * in the hub's own order, because "what is worth looking at tonight" is the hub's decision and the
     * hub already made it when it put that row first. Live channels are not candidates: a channel has
     * no first frame to sell, and a billboard you cannot pause is not a billboard.
     */
    private fun spotlight(heroes: List<Row>, rest: List<Row>, ctx: AppContext): List<Item> {
        val pool = heroes.firstOrNull()?.items ?: firstArtworkRow(rest) ?: return emptyList()
        val max = ctx.theme.token("hero", "maxItems", 5.0).toInt().coerceIn(1, 10)
        return pool.filter { eligible(it) }.take(max)
    }

    private fun firstArtworkRow(rows: List<Row>): List<Item>? =
        rows.firstOrNull { r -> r.items.any { eligible(it) } }?.items

    /** Needs something to look at, and needs to be a title rather than a channel. */
    private fun eligible(item: Item): Boolean = !item.isLive() && item.artFor(true) != null

    /**
     * Billboard plus the genre strip, as one full-bleed header. The billboard is a [Billboard], so it
     * rotates and shows where it is in the rotation; the chips below it are the hub's own genres.
     */
    private fun billboardHeader(ctx: AppContext, host: ScreenHost, items: List<Item>, rows: List<Row>): View {
        val bb = Billboard(ctx, host)
        billboard = bb
        bb.setItems(items)

        val wrapper = LinearLayout(ctx.app)
        wrapper.orientation = LinearLayout.VERTICAL
        wrapper.clipChildren = false
        wrapper.addView(bb.view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        wrapper.addView(chips(ctx, host, allGenres(rows)))
        return wrapper
    }

    /**
     * Genre chips: the genres this profile's rows actually contain, most frequent first.
     * Tapping one runs a hub search, so the results are the hub's, not a local filter.
     */
    private fun chips(ctx: AppContext, host: ScreenHost, genresIn: List<String>): View {
        val theme = ctx.theme
        val row = LinearLayout(ctx.app)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.setMargins(theme.gutter, theme.px(22f), theme.gutter, theme.px(10f))
        row.layoutParams = lp

        val genres = genresIn.take(theme.token("chips", "max", 8.0).toInt())
        for ((index, genre) in genres.withIndex()) {
            val chip = Views.text(ctx, "chip", 20f, theme.color("ink1", "#E8E8E8"), genre)
            chip.setPadding(theme.px(20f), theme.px(10f), theme.px(20f), theme.px(10f))
            chip.background = Views.rounded(theme.surface, theme.radius("chip", 999f), theme.px(1f), theme.line)
            chip.isFocusable = true
            chip.isClickable = true
            chip.setOnClickListener { host.show("search", genre) }
            chip.setOnFocusChangeListener { v, has ->
                v.setBackgroundColor(if (has) theme.ink else theme.surface)
                (v as android.widget.TextView).setTextColor(if (has) android.graphics.Color.BLACK else theme.color("ink1", "#E8E8E8"))
            }
            val clp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            clp.marginEnd = if (index < genres.size - 1) theme.px(12f) else 0
            row.addView(chip, clp)
        }
        return row
    }

    /** Every genre the hub sent, most frequent first — the chip strip's whole data source. */
    private fun allGenres(rows: List<Row>): List<String> =
        rows.flatMap { r -> r.items.flatMap { it.genres } }.groupingBy { it }.eachCount()
            .entries.sortedByDescending { it.value }.map { it.key }
}
