package com.scenix.tv.core

import androidx.media3.exoplayer.ExoPlayer

/**
 * The app plugin host — `design/plugin-architecture.md` §3.
 *
 * Five kinds of plugin, one interface each, plus a `RowLayout` value object. NOTHING here knows
 * about specific content: a new card visual, row, rule, player behaviour or screen is a new file
 * under `plugins/` plus ONE line in `PluginCatalog.kt`. No existing plugin's logic changes.
 */

interface Plugin {
    /** Registry key for this kind of plugin (also what the hub manifest lists). */
    val id: String
    /** Human label, used by the Settings screen when it prints the local plugin list. */
    val title: String get() = id
    /** Sort order inside its registry. Registries are always served sorted by this. */
    val order: Int get() = 0
}

// ---------------------------------------------------------------------------------------------
// Geometry a card must fit into. Theme tokens produce the default; a RowPlugin may override it
// (that is how `top10` gets its numeral gutter without any card plugin changing).
// ---------------------------------------------------------------------------------------------
data class RowLayout(
    /** Total width of one card slot in the horizontal list, including `artLeft`. */
    val slotWidth: Int,
    /** Width of the artwork inside the slot. */
    val artWidth: Int,
    /** Height of the artwork inside the slot. */
    val artHeight: Int,
    /** Left inset of the artwork — space reserved for row decorations (big numerals). */
    val artLeft: Int,
    /** Gap between card slots. */
    val gap: Int,
    /** Draw title + caption block under the art. */
    val caption: Boolean,
    /** One card fills the row (billboard-style rows). */
    val fullBleed: Boolean,
    /** Row-level gutter on the left of the whole row (safe area is separate). */
    val rowExtraLeft: Int,
    /** How wide a rank numeral may be, as a fraction of the art height (used by row plugins). */
    val numeralWidthRatio: Float = 0.55f,
    /** How far a rank numeral overlaps the art, same fraction. */
    val numeralOverlapRatio: Float = 0.229f,
    /** Absolute numeral metrics from the theme (design px, already scaled); 0 = use the ratios. */
    val numeralTextPx: Int = 0,
    val numeralOverlapPx: Int = 0,
    val numeralStrokePx: Int = 0,
    val numeralTextRatio: Float = 0.495f,
) {
    companion object {
        /**
         * Default geometry for a card type, straight from `assets/theme.json` -> `cards`, resolved
         * against this screen's pixel width (so a `fullBleed` card is exactly the content width).
         *
         * A `fullBleed` card is the WHOLE screen width, gutter included: the billboard is 1920 x 842
         * in the mockup and its text column carries the gutter itself. Insetting the art would make
         * the hero a different height on a different screen, which is what the theme cannot fix.
         */
        fun of(theme: Theme, screenW: Int, cardType: String): RowLayout {
            val spec = theme.cardSpec(cardType)
            val artW = if (spec.fullBleed) screenW.coerceAtLeast(1) else spec.width
            return RowLayout(
                slotWidth = artW + spec.numeralGutter,
                artWidth = artW,
                artHeight = Math.round(artW * spec.aspect).coerceAtLeast(1),
                artLeft = spec.numeralGutter,
                gap = theme.gap,
                caption = spec.caption,
                fullBleed = spec.fullBleed,
                rowExtraLeft = 0,
                numeralWidthRatio = theme.token("cards", "numeralWidthRatio", 0.55).toFloat(),
                numeralOverlapRatio = theme.token("cards", "numeralOverlapRatio", 0.229).toFloat(),
                numeralTextPx = theme.px(theme.token("cards", "numeralTextPx", 0.0).toFloat()),
                numeralOverlapPx = theme.px(theme.token("cards", "numeralOverlapPx", 0.0).toFloat()),
                numeralStrokePx = theme.px(theme.token("cards", "numeralStrokePx", 8.0).toFloat()),
                numeralTextRatio = theme.token("cards", "numeralTextRatio", 0.495).toFloat(),
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// cards/ — one renderer per hub `card` string
// ---------------------------------------------------------------------------------------------
interface CardPlugin : Plugin {
    /** The hub row's `card` value this renderer answers to ("poster" | "channel" | …). */
    val cardType: String

    /** Build the view tree for one card (called on RecyclerView create — must be cheap). */
    fun create(ctx: AppContext, host: ScreenHost, layout: RowLayout): CardHolder

    /** Fill it with an item. Called on every rebind, so it must reset everything it owns. */
    fun bind(holder: CardHolder, item: Item, position: Int, ctx: AppContext)

    /** Optional: release references (bitmaps are shared with ImageLoader, so usually nothing). */
    fun unbind(holder: CardHolder) {}

    /**
     * What a press on this card means. Detail for everything the viewer has not started; "continue
     * watching" goes straight into the player at the saved position, because a row that promises to
     * resume something has to resume it, not make the viewer press twice.
     */
    fun open(item: Item, ctx: AppContext, host: ScreenHost) {
        host.openDetail(item.identity, item)
    }
}

// ---------------------------------------------------------------------------------------------
// rows/ — the generic row renderer is in plugins/rows; a RowPlugin exists only to specialise
// ---------------------------------------------------------------------------------------------
interface RowPlugin : Plugin {
    /** Hub row id ("top10", "continue", …). */
    val rowId: String

    /** Override the geometry the theme picked for this row's card type. */
    fun layout(base: RowLayout): RowLayout = base

    /** Reshape the items the hub sent (rank, trim, re-order). Default: as-is. */
    fun items(row: Row): List<Item> = row.items

    /** Add row-specific views to a bound card (numerals, ribbons, …). */
    fun decorate(holder: CardHolder, item: Item, position: Int, ctx: AppContext) {}

    /** Where this row's items come from when the screen asks for more than /home gave. */
    fun fetchFrom(): String = "row"

    /** Show "Explore all ›" in the row header. Null = let the theme's threshold decide. */
    fun explore(): Boolean? = null

    /** Extra left gutter for this row, on top of the 64 px safe area (numerals hang left). */
    fun rowGutter(): Int = 0
}

// ---------------------------------------------------------------------------------------------
// rules/ — client-side rules applied to every row before it renders
// ---------------------------------------------------------------------------------------------
data class RuleCtx(val profile: Profile, val app: AppContext)

interface RulePlugin : Plugin {
    /** Return the item to show, or null to drop it. May annotate (re-order sources, add flags). */
    fun applyItem(item: Item, rctx: RuleCtx): Item? = item

    /** Return the row to show, or null to drop the whole row. */
    fun applyRow(row: Row, rctx: RuleCtx): Row? = row
}

// ---------------------------------------------------------------------------------------------
// player/ — one plugin per source `kind` (http | hls | dash | torrent)
// ---------------------------------------------------------------------------------------------
interface PlayerPlugin : Plugin {
    /** The `sources[].kind` (and /app/v1/play `kind`) this plugin drives. */
    val kind: String

    /** Shown as a toast when playback starts (the torrent warning). */
    val warning: String? get() = null

    /** The risk chip for the overlay + "Play from" list. Null = no chip. */
    fun riskLabel(source: Source): String? = source.risk.takeIf { it.isNotBlank() }

    /** Colour token for the risk chip ("match" | "warn" | "brand"). */
    fun riskToken(source: Source): String = "warn"

    /**
     * Buffering policy for this kind, chosen before the player is built (LoadControl is immutable
     * after that). Null = the theme default: 30 s behind, 120 s ahead.
     */
    fun loadControl(app: AppContext): androidx.media3.exoplayer.LoadControl? = null

    /** Configure the ExoPlayer for this kind: ceilings, buffering, track selection. */
    fun configure(player: ExoPlayer, app: AppContext, source: Source, target: PlayTarget)

    /** One honest status line for the overlay readout (quality ceiling, ladder state…). */
    fun statusLine(source: Source, target: PlayTarget): String = source.quality

    /**
     * Can this TV actually open this source right now? The player and the "Play from" list ask
     * before offering anything, so a source that needs hardware or an app this set does not have is
     * listed honestly instead of failing after a tap.
     */
    fun openable(app: AppContext, source: Source): Boolean = true

    /**
     * True when this plugin does not feed Media3 at all: it hands the title to something else (a
     * companion stream engine). The player screen then never builds an ExoPlayer for it.
     */
    fun external(): Boolean = false

    /**
     * The hand-off for an [external] plugin. Returns the line the player overlay should print —
     * "Opened with …" or why nothing was opened. Never throws.
     */
    fun openExternally(app: AppContext, item: Item, source: Source, target: PlayTarget?): String =
        "No engine on this TV for ${Format.provider(source).lowercase()} sources"
}

// ---------------------------------------------------------------------------------------------
// nav/ — screens. The nav row renders whatever this registry holds.
// ---------------------------------------------------------------------------------------------
interface ScreenPlugin : Plugin {
    /** Route segment for deep links: tv://<route>… */
    val route: String get() = id

    /** Which profiles may see it: "all" | "adult" | "kids". */
    val scope: String get() = "all"

    /** Show this screen in the top nav row. */
    val showInNav: Boolean get() = true

    /**
     * A capability this screen needs from the backend, named as the backend names it in its manifest
     * (`live-tv`, `search`, …). Null means any backend can host this screen. A screen whose whole
     * subject this backend cannot supply is hidden from the nav instead of shown broken — this is the
     * only branching the UI does between a hub and standalone mode (design/deployment-modes.md
     * Rule 2).
     */
    val requires: String? get() = null

    /** Build the screen into `host.container()`. */
    fun render(host: ScreenHost)

    /** Back pressed while this screen is up. True = handled here. */
    fun onBack(host: ScreenHost): Boolean = false

    /** Called when the screen is popped or replaced. */
    fun onLeave(host: ScreenHost) {}
}
