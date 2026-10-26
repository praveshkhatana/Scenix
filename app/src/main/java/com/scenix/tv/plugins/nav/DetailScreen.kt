package com.scenix.tv.plugins.nav

import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.scenix.tv.core.Format
import com.scenix.tv.core.Focus
import com.scenix.tv.core.Item
import com.scenix.tv.core.Row
import com.scenix.tv.core.RowLayout
import com.scenix.tv.core.ScreenHost
import com.scenix.tv.core.ScreenPlugin
import com.scenix.tv.core.Source
import com.scenix.tv.core.Views
import com.scenix.tv.plugins.cards.HeroContent
import com.scenix.tv.plugins.cards.HeroCard
import com.scenix.tv.plugins.rows.RowAdapter
import com.scenix.tv.plugins.rows.statusRow

/**
 * Detail: the hero header (the same `hero` card plugin Home's billboard uses, at `compact` scale),
 * the merged facts the hub sent, and "Play from" — one entry per source, in the order
 * plugins/rules/RiskOrder.kt put them, each with its provider, quality, size and risk chip.
 *
 * "More like this" is not a hub endpoint yet, so the screen derives it honestly from the data it can
 * already reach: other items the hub sent that share a genre. When the hub grows a
 * `/app/v1/similar`, this becomes one line and nothing else moves.
 */
object DetailScreen : ScreenPlugin {
    override val id get() = "detail"
    override val title get() = "Details"
    override val route get() = "detail"
    override val showInNav = false
    override val order = 300

    override fun render(host: ScreenHost) {
        val ctx = host.app
        val theme = ctx.theme
        val container = host.container()
        val identity = host.arg() ?: ""

        val root = LinearLayout(ctx.app)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(theme.bg)
        container.addView(
            root,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )

        val (rv, adapter) = RowAdapter.newRecyclerView(ctx, host)
        root.addView(rv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        adapter.submit(listOf(statusRow(ctx, "Loading\u2026") to false), emptyList())

        val seed = host.seedItem(identity)
        if (seed != null) show(host, container, root, adapter, seed)
        ctx.hub.meta(identity, seed?.type) { item, error ->
            BrowseHelper.runOnUiThread {
                if (container.indexOfChild(root) < 0) return@runOnUiThread
                if (item == null) {
                    if (seed == null) {
                        // 403 on meta = this profile is not shown this title. Not an outage, and a
                        // seed already on screen keeps rendering.
                        val msg = when {
                            error != null && com.scenix.tv.core.HubError.forbidden(error) ->
                                BrowseHelper.hiddenMessage(ctx)
                            error != null -> BrowseHelper.offlineMessage(ctx, error)
                            else -> "The hub has no entry for this title."
                        }
                        adapter.submit(listOf(statusRow(ctx, msg) to false), emptyList())
                    }
                    return@runOnUiThread
                }
                show(host, container, root, adapter, item)
                moreLikeThis(ctx, host, adapter, item)

                // Scenix opens a title page standing on Play. The hero's Play button carries the
                // tag "play" (set by the hero card plugin), so this stays true for whatever the card
                // plugin renders — the screen does not know or care how the button is built.
                // unconditional: the hero art can already hold focus, and then Play was never reached,
                // which is how OK on a title page looked dead. Standing on Play is the point.
                rv.post {
                    (root.findViewWithTag<View>("play"))?.requestFocus()
                        ?: Focus.firstShelfCard(rv)?.requestFocus()
                }
            }
        }
    }

    private fun show(
        host: ScreenHost,
        container: View,
        root: LinearLayout,
        adapter: RowAdapter,
        item: Item,
    ) {
        val ctx = host.app
        val theme = ctx.theme

        val heroLayout = RowLayout.of(theme, ctx.screenW, "hero")
        val holder = HeroCard.create(ctx, host, heroLayout)
        HeroContent.fill(holder, item, ctx, compact = true)
        holder.root.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        )

        val headers = ArrayList<Pair<View, Boolean>>()
        headers.add(holder.root to true)
        headers.add(facts(ctx, item) to false)
        headers.add(playFrom(ctx, host, item) to false)
        adapter.submit(headers, emptyList())
    }

    /** The merged facts: one line per fact the hub actually sent, nothing invented. */
    private fun facts(ctx: com.scenix.tv.core.AppContext, item: Item): View {
        val theme = ctx.theme
        val block = LinearLayout(ctx.app)
        block.orientation = LinearLayout.VERTICAL
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.setMargins(theme.gutter, theme.px(28f), theme.gutter, theme.px(10f))
        block.layoutParams = lp

        val lines = ArrayList<String>()
        if (item.genres.isNotEmpty()) lines.add(item.genres.joinToString(" \u00B7 "))
        if (item.languages.isNotEmpty()) lines.add("Audio: " + Format.languages(item))
        if (item.maturity.isNotBlank()) lines += "Maturity: ${item.maturity}"
        if (item.durationMs > 0) lines += "Runtime: ${Format.duration(item.durationMs)}"
        if (item.addedAt > 0) lines += Format.ago(item.addedAt)
        if (item.type == "channel" || item.isLive()) {
            item.live?.now?.let { lines += "Now: $it" }
            item.live?.quality?.let { lines += "Current quality: $it" }
        }
        if (item.progress != null && item.progress.watchedMs > 0) {
            lines += "Watched: ${Format.clock(item.progress.positionMs)} of ${Format.duration(item.progress.watchedMs)}"
        }
        for (line in lines) {
            val tv = Views.text(ctx, "sub", 23f, theme.ink2, line, maxLines = 2)
            val tlp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            tlp.bottomMargin = theme.px(10f)
            block.addView(tv, tlp)
        }
        return block
    }

    /**
     * "Play from": every source the hub merged for this title, in the client's own ranking
     * (adaptive first, torrent last). The chip is the plugin's risk label, so the warning a torrent
     * deserves is visible before the user commits to it.
     */
    private fun playFrom(ctx: com.scenix.tv.core.AppContext, host: ScreenHost, item: Item): View {
        val theme = ctx.theme
        val wrap = LinearLayout(ctx.app)
        wrap.orientation = LinearLayout.VERTICAL
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.setMargins(theme.gutter, theme.px(26f), theme.gutter, theme.px(40f))
        wrap.layoutParams = lp

        wrap.addView(
            Views.text(ctx, "rowTitle", 26f, theme.ink, "Play from", bold = true),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )

        if (item.sources.isEmpty()) {
            val none = Views.text(ctx, "sub", 22f, theme.ink3, "The hub found no playable source for this title.")
            val nlp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            nlp.topMargin = theme.px(14f)
            wrap.addView(none, nlp)
            return wrap
        }

        // The hub can hand back a dozen feeds for one channel. The best few go on screen here; the
        // whole ranked list stays reachable from the player's Source menu.
        val ranked = ctx.ranked(item)
        val shown = ranked.take(maxOf(1, ctx.theme.token("detail", "maxSources", 6.0).toInt()))
        for ((index, source) in shown.withIndex()) {
            wrap.addView(sourceRow(ctx, host, item, source, index), rowLp(ctx, index == 0))
        }
        if (ranked.size > shown.size) {
            wrap.addView(
                Views.text(
                    ctx, "liMeta", 20f, ctx.theme.ink3,
                    "${ranked.size - shown.size} more sources in the player's Source menu",
                    maxLines = 2,
                ),
                rowLp(ctx, false),
            )
        }
        return wrap
    }

    private fun rowLp(ctx: com.scenix.tv.core.AppContext, first: Boolean): LinearLayout.LayoutParams {
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = ctx.theme.px(if (first) 16f else 12f)
        return lp
    }

    /**
     * One entry in "Play from".
     *
     * Every source is LISTED, including kinds this TV cannot play — a torrent magnet has a size and a
     * risk that are worth seeing. Whether it can be OPENED is decided by the player registry: a kind
     * with no plugin gets an inline note instead of a player. That keeps "what this device can play"
     * in exactly one place ([com.scenix.tv.core.Registries.players]).
     */
    private fun sourceRow(
        ctx: com.scenix.tv.core.AppContext,
        host: ScreenHost,
        item: Item,
        source: Source,
        index: Int,
    ): View {
        val theme = ctx.theme
        val wrap = LinearLayout(ctx.app)
        wrap.orientation = LinearLayout.VERTICAL

        val row = LinearLayout(ctx.app)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(theme.px(24f), theme.px(20f), theme.px(24f), theme.px(20f))
        row.background = Views.rounded(theme.surface, theme.radius("listItem", 6f))
        row.isFocusable = true
        row.isClickable = true

        val label = Views.text(
            ctx, "li", 24f, theme.ink,
            source.label.ifBlank { Format.provider(source) },
            bold = true,
        )
        row.addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val detail = Views.text(
            ctx, "liMeta", 21f, theme.ink2,
            listOf(
                "Source ${index + 1}",
                Format.provider(source),
                source.quality,
                Format.size(source.sizeBytes),
            ).filter { it.isNotBlank() }.joinToString(" \u00B7 "),
        )
        row.addView(detail, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // Exact lookup only: a fallback plugin would claim a capability the TV does not have.
        val plugin = ctx.pluginFor(source)
        val playable = plugin?.openable(ctx, source) == true
        val external = plugin?.external() == true
        val risk = plugin?.riskLabel(source)
            ?: listOfNotNull(source.kind.takeIf { !playable }, source.risk.takeIf { it.isNotBlank() })
                .joinToString(" \u00B7 ")
        if (!risk.isNullOrBlank()) {
            val chip = Views.text(ctx, "chip", 19f, theme.color(plugin?.riskToken(source) ?: "warn", "#F2C33A"), risk)
            chip.setPadding(theme.px(12f), theme.px(4f), theme.px(12f), theme.px(4f))
            chip.background = Views.rounded(
                theme.color("chipBg", "#33FFFFFF"), theme.radius("chip", 4f),
            )
            val clp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            clp.marginStart = theme.px(18f)
            row.addView(chip, clp)
        }

        if (source.why.isNotEmpty()) {
            val why = Views.text(
                ctx, "liMeta", 19f, theme.ink3,
                "why: " + source.why.joinToString(" \u00B7 "),
                maxLines = 2,
            )
            val wlp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            wlp.topMargin = theme.px(8f)
            wlp.marginStart = theme.px(24f)
            wrap.addView(why, wlp)
        }

        val note = Views.text(ctx, "liMeta", 20f, theme.color("kids", "#F2C33A"), "")
        val nlp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        nlp.topMargin = theme.px(10f)
        note.visibility = View.GONE

        row.setOnClickListener {
            when {
                !playable -> {
                    // Inline state, not a toast and not a player that can only fail.
                    note.text = "${Format.provider(source)} \u00B7 this TV cannot open it \u2014 listed for reference"
                    note.visibility = View.VISIBLE
                }
                external -> {
                    // A hand-off: ask the hub for the address, then let the plugin hand it over.
                    note.text = "Asking the hub for this source\u2026"
                    note.visibility = View.VISIBLE
                    val p = plugin ?: return@setOnClickListener
                    ctx.hub.play(item.identity, source.provider, ctx.profile?.id, item.type) { play, error ->
                        BrowseHelper.runOnUiThread {
                            if (row.parent == null) return@runOnUiThread
                            note.text = when {
                                play == null ->
                                    "The hub could not open this source (${error ?: "no answer"})"
                                // The hub fetched this copy for us, so it is ordinary seekable HTTP
                                // and plays here whatever the source itself is.
                                play.playable() -> {
                                    host.play(item, source)
                                    "Streaming through the hub \u2014 it fetched this copy for us"
                                }
                                else -> p.openExternally(ctx, item, source, play)
                            }
                            note.visibility = View.VISIBLE
                        }
                    }
                }
                else -> host.play(item, source)
            }
        }
        rowFocus(row, ctx)

        wrap.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        wrap.addView(note, nlp)
        return wrap
    }

    /** Rows derived from shared genres — the stand-in for a hub `/similar` endpoint. */
    private fun moreLikeThis(
        ctx: com.scenix.tv.core.AppContext,
        host: ScreenHost,
        adapter: RowAdapter,
        item: Item,
    ) {
        if (item.genres.isEmpty()) return
        ctx.hub.home(ctx.profile?.id) { rows, _ ->
            BrowseHelper.runOnUiThread {
                val genres = item.genres.toHashSet()
                val similar = rows.orEmpty().flatMap { it.items }
                    .filter { it.identity != item.identity && it.genres.any { g -> genres.contains(g) } }
                    .distinctBy { it.identity }
                    .take(12)
                if (similar.isEmpty()) return@runOnUiThread
                adapter.appendRows(
                    listOf(Row(id = "similar", title = "More like ${item.title}", card = "poster", items = similar)),
                )
            }
        }
    }
}

/** Focus language for the mockup's `.side .li` rows: fill, not scale, so a long list stays readable. */
private fun rowFocus(row: LinearLayout, ctx: com.scenix.tv.core.AppContext) {
    val theme = ctx.theme
    row.setBackgroundColor(theme.surface)
    row.setOnFocusChangeListener { v, has ->
        v.setBackgroundColor(if (has) theme.color("surfHover", "#2F2F2F") else theme.surface)
    }
}
