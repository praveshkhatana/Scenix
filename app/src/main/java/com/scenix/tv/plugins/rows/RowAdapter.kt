package com.scenix.tv.plugins.rows

import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.scenix.tv.core.AppContext
import com.scenix.tv.core.Camera
import com.scenix.tv.core.CardHolder
import com.scenix.tv.core.CardPlugin
import com.scenix.tv.core.Focus
import com.scenix.tv.core.Item
import com.scenix.tv.core.Preview
import com.scenix.tv.core.Row
import com.scenix.tv.core.RowLayout
import com.scenix.tv.core.ScreenHost
import com.scenix.tv.core.Views

/**
 * The generic row renderer: a vertical RecyclerView of rows, each row a horizontal RecyclerView of
 * cards. It holds no content knowledge — the row's `card` string selects the [CardPlugin] through
 * the registry, and the row's `id` selects an optional [com.scenix.tv.core.RowPlugin] only when a
 * row needs custom geometry or decoration. A row the app has never seen renders as a poster row.
 *
 * `headers` lets a screen put non-row views (billboard, chips) at the head of the same scrolling
 * list, which is what keeps D-pad up/down continuous from the nav through to the last row.
 */
class RowAdapter(
    private val ctx: AppContext,
    private val host: ScreenHost,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private sealed interface Section

    private class HeaderSection(val view: View, val fullBleed: Boolean) : Section

    private class RowSection(val row: Row) : Section

    private var sections: MutableList<Section> = ArrayList()

    /** One shared pool for every horizontal list: cards are cheap to reuse across rows. */
    private val pool = RecyclerView.RecycledViewPool()

    /** Replace everything: `headers` first (billboard, chips), then the hub's rows. */
    fun submit(headers: List<Pair<View, Boolean>>, rows: List<Row>) {
        val next = ArrayList<Section>(headers.size + rows.size)
        for ((view, bleed) in headers) next.add(HeaderSection(view, bleed))
        for (r in rows) next.add(RowSection(r))
        sections = next
        notifyDataSetChanged()
    }

    fun setRows(rows: List<Row>) = submit(emptyList(), rows)

    /** Append rows without rebuilding the screen (infinite scroll / "explore all"). */
    fun appendRows(rows: List<Row>) {
        if (rows.isEmpty()) return
        val start = sections.size
        for (r in rows) sections.add(RowSection(r))
        notifyItemRangeInserted(start, rows.size)
    }

    override fun getItemCount(): Int = sections.size

    override fun getItemViewType(position: Int): Int =
        if (sections[position] is HeaderSection) TYPE_HEADER else TYPE_ROW

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val theme = ctx.theme
        if (viewType == TYPE_HEADER) return HeaderHolder(FrameLayout(parent.context))

        val item = LinearLayout(parent.context)
        item.orientation = LinearLayout.VERTICAL
        item.layoutParams = RecyclerView.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        )

        // Row header: title + optional "Explore all ›"
        val head = LinearLayout(parent.context)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        val hlp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        hlp.leftMargin = theme.gutter
        hlp.rightMargin = theme.gutter
        hlp.bottomMargin = theme.px(14f)
        item.addView(head, hlp)

        val title = Views.text(ctx, "rowTitle", 26f, theme.ink, "", bold = true)
        head.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val explore = Views.text(ctx, "explore", 18f, theme.ink2, "")
        explore.setPadding(theme.px(12f), theme.px(6f), theme.px(12f), theme.px(6f))
        head.addView(
            explore,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )

        // Horizontal card list
        // A focused card is 10 % bigger than its slot, so no view between the card and the window may
        // cut it off: clipChildren=false all the way up is what stops the ring being shaved on one
        // side, which is what makes a shelf look crooked on a real TV.
        item.clipChildren = false
        val cards = RecyclerView(parent.context)
        cards.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        cards.layoutManager = LinearLayoutManager(parent.context, LinearLayoutManager.HORIZONTAL, false)
        cards.setRecycledViewPool(pool)
        cards.itemAnimator = null
        cards.isFocusable = false
        cards.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        cards.clipToPadding = false
        cards.clipChildren = false
        cards.overScrollMode = View.OVER_SCROLL_NEVER
        cards.setItemViewCacheSize(CARD_CACHE)
        // A shelf that slides sideways must not be opening a trailer under cards flying past it.
        cards.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                Preview.cancelPending()
                // The frame is glued to its card, so it moves the moment the shelf does — with no
                // animation of its own, or it reads as a lag.
                com.scenix.tv.core.FocusGlass.shift(dx, dy)
            }
        })
        Focus.drawFocusedOnTop(cards)
        item.addView(cards)

        return RowHolder(item, title, explore, cards)
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val section = sections[position]
        if (holder is HeaderHolder && section is HeaderSection) {
            val v = section.view
            (v.parent as? ViewGroup)?.removeView(v)
            holder.frame.removeAllViews()
            holder.frame.addView(
                v,
                FrameLayout.LayoutParams(
                    if (section.fullBleed) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            holder.setIsRecyclable(false)
            return
        }
        if (holder is RowHolder && section is RowSection) holder.bind(section.row)
    }

    private class HeaderHolder(val frame: FrameLayout) : RecyclerView.ViewHolder(frame) {
        init { setIsRecyclable(false) }
    }

    private inner class RowHolder(
        itemView: View,
        val title: TextView,
        val explore: TextView,
        val cards: RecyclerView,
    ) : RecyclerView.ViewHolder(itemView) {

        fun bind(row: Row) {
            val theme = ctx.theme
            title.text = row.title
            title.visibility = if (row.title.isBlank()) View.GONE else View.VISIBLE

            val rowPlugin = ctx.registries.rowFor(row.id)
            val cardPlugin = ctx.registries.cardFor(row.card)

            val threshold = theme.token("rows", "exploreThreshold", 6.0).toInt()
            val showExplore = rowPlugin?.explore() ?: (row.items.size >= threshold)
            explore.text = if (showExplore) "Explore all \u203A" else ""
            explore.visibility = if (showExplore) View.VISIBLE else View.GONE
            if (showExplore) {
                explore.setOnClickListener { host.show(ROW_SCREEN_ID, row.id) }
                explore.isFocusable = true
                explore.onFocusChangeListener = View.OnFocusChangeListener { v, has ->
                    v.setBackgroundColor(if (has) theme.surface2 else Color.TRANSPARENT)
                }
            } else {
                explore.setOnClickListener(null)
                explore.isFocusable = false
            }

            if (cardPlugin == null) {
                cards.adapter = null
                cards.visibility = View.GONE
                return
            }
            cards.visibility = View.VISIBLE

            // Geometry: theme default for the card type, then any row-plugin override.
            val base = RowLayout.of(theme, ctx.screenW, row.card)
            val layout = rowPlugin?.layout(base) ?: base

            val gutter = theme.gutter + (rowPlugin?.rowGutter() ?: 0)
            // Vertical padding is the room a focused card needs to grow into. `clipToPadding = false`
            // keeps the first card inside the gutter while the scaled card may still reach into it.
            val grow = ((layout.artHeight * (theme.focusScale - 1f)) / 2f).toInt() +
                theme.focusRingPx + theme.focusElevationPx + theme.px(4f)
            val vpad = if (layout.fullBleed) 0 else grow.coerceAtLeast(0)
            if (cards.paddingStart != gutter || cards.paddingTop != vpad) {
                cards.setPadding(gutter, vpad, gutter, vpad)
            }

            cards.swapAdapter(CardsAdapter(row, rowPlugin, cardPlugin, layout), false)
        }
    }

    /** One horizontal strip of cards inside a row. */
    private inner class CardsAdapter(
        val row: Row,
        val rowPlugin: com.scenix.tv.core.RowPlugin?,
        val cardPlugin: CardPlugin,
        val layout: RowLayout,
    ) : RecyclerView.Adapter<CardHolder>() {

        // The hub may cap a row (`"max": 10`); a row plugin may also reorder or trim.
        private val items: List<Item> = run {
            val base = rowPlugin?.items(row) ?: row.items
            if (row.max > 0) base.take(row.max) else base
        }

        override fun getItemCount(): Int = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CardHolder {
            val plugin = cardPlugin
            val holder = CardHolder.build(ctx, layout, host, onOpen = { item -> plugin.open(item, ctx, host) })
            holder.root.layoutParams = RecyclerView.LayoutParams(layout.slotWidth, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                rightMargin = layout.gap
            }
            return holder
        }

        override fun onBindViewHolder(holder: CardHolder, position: Int) {
            val item = items.getOrElse(position) { return }
            holder.boundItem = item

            // One focus listener, three Scenix behaviours: the trailer dwell, the camera that frames
            // the card, and MENU on a card the viewer is standing on.
            Focus.setCardHooks(
                holder.root,
                onFocus = { v, has ->
                    Preview.onCardFocus(ctx, holder.boundItem, has)
                    if (has) Camera.follow(v, ctx)
                },
                onMenu = { _ ->
                    holder.boundItem?.let { com.scenix.tv.plugins.cards.CardMenu.show(ctx, host, it) }
                },
            )

            if (holder.needsEntrance) {
                holder.needsEntrance = false
                holder.animateIn(position.coerceAtMost(MAX_STAGGER))
            }
            cardPlugin.bind(holder, item, position, ctx)
            rowPlugin?.decorate(holder, item, position, ctx)
        }

        override fun onViewRecycled(holder: CardHolder) {
            holder.boundItem = null
            holder.resetMotion()
            Focus.setCardHooks(holder.root, null, null)
            cardPlugin.unbind(holder)
        }
    }

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_ROW = 1
        private const val CARD_CACHE = 8

        /** How far the row's fade-up stagger runs before later cards simply appear. */
        private const val MAX_STAGGER = 7

        /** Screen id used by "Explore all" (plugins/nav/RowScreen.kt). */
        const val ROW_SCREEN_ID = "row"

        /** Build the vertical list used by every row-based screen. */
        fun newRecyclerView(ctx: AppContext, host: ScreenHost): Pair<RecyclerView, RowAdapter> {
            val rv = RecyclerView(ctx.app)
            rv.layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
            )
            rv.layoutManager = LinearLayoutManager(ctx.app, LinearLayoutManager.VERTICAL, false)
            rv.itemAnimator = null
            rv.clipToPadding = false
            rv.overScrollMode = View.OVER_SCROLL_NEVER
            rv.setItemViewCacheSize(6)
            rv.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                    Preview.cancelPending()
                    com.scenix.tv.core.FocusGlass.shift(0, dy)
                }
            })
            val adapter = RowAdapter(ctx, host)
            rv.adapter = adapter
            Focus.tuneRecyclerView(rv)
            return rv to adapter
        }
    }
}

/** A plain status line that sits where a row would, for loading / error states. */
fun statusRow(ctx: AppContext, message: String): View {
    val tv = Views.text(ctx, "meta", 20f, ctx.theme.ink2, message, maxLines = 2)
    tv.setPadding(ctx.theme.gutter, ctx.theme.px(20f), ctx.theme.gutter, ctx.theme.px(20f))
    return tv
}
