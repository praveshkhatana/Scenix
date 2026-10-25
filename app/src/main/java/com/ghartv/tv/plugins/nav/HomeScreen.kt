package com.scenix.tv.plugins.nav

import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import com.scenix.tv.core.Focus
import com.scenix.tv.core.Item
import com.scenix.tv.core.NavBar
import com.scenix.tv.core.Row
import com.scenix.tv.core.RowLayout
import com.scenix.tv.core.ScreenHost
import com.scenix.tv.core.ScreenPlugin
import com.scenix.tv.core.Views
import com.scenix.tv.plugins.cards.HeroCard
import com.scenix.tv.plugins.rows.RowAdapter
import com.scenix.tv.plugins.rows.statusRow

/**
 * Home: nav, then one scrolling list of the hub's rows.
 *
 * Two things Home adds on top of [BrowseHelper], and both are data-driven rather than content rules:
 *  - any row whose `card` is `hero` is pulled out and rendered full-bleed as the billboard, using
 *    the `hero` card plugin — the same plugin a hero row inside the list would use, so the billboard
 *    and a hero card can never look different;
 *  - the chip strip under the billboard is the genres the hub actually sent, most frequent first.
 *    A new genre appears in the strip the day the hub starts tagging it; nothing in this file changes.
 */
object HomeScreen : ScreenPlugin {
    override val id get() = "home"
    override val title get() = "Home"
    override val route get() = ""
    override val order = 10

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

        val (rv, adapter) = RowAdapter.newRecyclerView(ctx, host)
        root.addView(rv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        adapter.submit(listOf(statusRow(ctx, "Loading\u2026") to false), emptyList())

        ctx.hub.home(ctx.profile?.id) { page, error ->
            BrowseHelper.runOnUiThread {
                if (container.indexOfChild(root) < 0) return@runOnUiThread
                if (error != null && page == null) {
                    val msg = if (com.scenix.tv.core.HubError.forbidden(error)) {
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
                val heroes = rows.filter { it.card == "hero" && it.items.isNotEmpty() }
                // A row the hub answered with nothing gets no header: a shelf with no cards under it
                // ("Continue watching" before anyone has watched anything) reads as broken, and Netflix
                // does not print it either.
                val rest = rows.filter { it.card != "hero" && it.items.isNotEmpty() }

                val headers = ArrayList<Pair<View, Boolean>>()
                if (ctx.offline) headers.add(BrowseHelper.offlineChip(ctx) to false)
                val billboard: Item? = heroes.firstOrNull()?.items?.firstOrNull()
                if (billboard != null) {
                    headers.add(billboard(ctx, host, billboard, rest) to true)
                }
                if (rest.isEmpty()) headers.add(statusRow(ctx, BrowseHelper.emptyMessage(ctx)) to false)
                adapter.submit(headers, rest)

                // Netflix: when the screen opens the viewer is already standing on the first card of
                // the first shelf, so one press of OK opens something and Up reaches the billboard.
                // Only when nothing has focus yet — a refresh must never yank focus away from wherever
                // the viewer is standing.
                if (rv.findFocus() == null) {
                    rv.post { Focus.firstShelfCard(rv)?.requestFocus() }
                }
            }
        }
    }

    /** The billboard, built by the `hero` card plugin so Detail's header matches it exactly. */
    private fun billboard(ctx: com.scenix.tv.core.AppContext, host: ScreenHost, item: Item, rows: List<Row>): View {
        val layout = RowLayout.of(ctx.theme, ctx.screenW, "hero")
        val holder = HeroCard.create(ctx, host, layout)
        HeroCard.bind(holder, item, 0, ctx)
        holder.root.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        val wrapper = LinearLayout(ctx.app)
        wrapper.orientation = LinearLayout.VERTICAL
        wrapper.addView(holder.root)
        wrapper.addView(chips(ctx, host, allGenres(rows)))
        return wrapper
    }

    /**
     * Genre chips: the genres this profile's rows actually contain, most frequent first.
     * Tapping one runs a hub search, so the results are the hub's, not a local filter.
     */
    private fun chips(ctx: com.scenix.tv.core.AppContext, host: ScreenHost, genresIn: List<String>): View {
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
                (v as android.widget.TextView).setTextColor(if (has) Color.BLACK else theme.color("ink1", "#E8E8E8"))
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
