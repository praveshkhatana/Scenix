package com.scenix.tv

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.scenix.tv.core.AppContext
import com.scenix.tv.core.CardActions
import com.scenix.tv.core.Focus
import com.scenix.tv.core.Item
import com.scenix.tv.core.Views

/**
 * The shelf tile: the one card every look at a title uses — Home, Search, Sections, My List.
 *
 * Extracted from BrowseActivity so the design-system Studio renders the *same* tile the television
 * does instead of a second drawing of it. The adapter still owns row policy (see-all, genres,
 * continue dismissal); the host supplies the routes and the profile.
 */
internal interface ShelfHost {
    fun app(): AppContext
    fun api(): BrowseApi
    fun profileId(): String
    fun heroPos(): Int
    fun navAnchorId(): Int
    fun onCardFocused(card: View, item: Item)
    fun onSlideFocused(position: Int)
    fun focusFeatured(position: Int)
    fun openDetail(item: Item)
    fun openLive(item: Item)
    fun openRowFull(row: BrowseRow)
    /** Home offers to take a whole shelf off the page; a section or a search grid shows titles that
     *  belong to many shelves, so it offers only the title's own actions. */
    fun managesRows(): Boolean = false
    /** The shelf is gone from the hub's Home record; the page drops it too. */
    fun onRowHidden(rowId: String) {}
}

internal class ShelfAdapter(
    private val activity: Activity,
    private val host: ShelfHost,
    private val row: BrowseRow,
) : RecyclerView.Adapter<ShelfCardHolder>() {
    private val app = host.app()
    private val api = host.api()
    private val profileId = host.profileId()

    private val theme = app.theme
    private val style = row.card
    // The featured tiles are the hero's slide list: focus on one turns the billboard above it.
    private val featured = row.id == "featured"
    // The pack owns the rail shape: Midnight turns ordinary poster shelves into wide 16:9 stills,
    // Ember keeps portraits but frames them. Ranked rows keep their numeral geometry.
    private val landscape = style == "poster" && theme.rowStyle == "landscape"
    private val poster = if (landscape) false else style == "poster" || style == "rank" || style == "hero"
    private val ranked = style == "rank"
    private val frame = theme.cardStyle == "frame"
    // The pack's card token picks the tile GEOMETRY (overlay/glass/below/frame); the material
    // is a separate decision. The Glass pack draws below-art captions yet wants its rails to
    // carry the same translucent plates as its hero, so tileMaterial declares the material
    // independently of the geometry.
    private val glass = theme.cardStyle == "glass" || theme.packString("tileMaterial", "") == "glass"
    // Apple's scene cards keep the name under the art instead of over it.
    private val below = theme.cardStyle == "below"
    private val captionH = when {
        below -> theme.space("captionBlock", 62f)
        frame -> theme.px(56f)
        else -> 0
    }
    // A pack may resize the poster lockup from its spacing tokens (Glass replicates the
    // reference's 264×373 tiles at 27px gaps); packs that omit the keys keep the classic
    // geometry byte for byte. The Kids contract squares its tiles from `cards.poster` instead.
    private val tileSquare = theme.packInt("tileSquare", 0) == 1
    private val cardW = if (poster && tileSquare) theme.posterTilePx().first
        else if (poster) theme.space("posterWidth", if (landscape) 480f else 280f)
        else theme.px(320f)
    private val cardH = if (poster && tileSquare) theme.posterTilePx().second
        else if (poster) theme.space("posterHeight", if (landscape) 270f else 420f)
        else theme.px(180f)
    private val radius = if (poster && tileSquare) theme.radius("tile", 24f)
        else theme.radius("card", 6f)
    // When the shelf is a slice of a bigger list, its end carries one more tile: the full list.
    private val seeAll = !featured && row.total > row.items.size
    // A tile's geometry is part of its identity. The recycled pool hands holders to whoever asks
    // next - a rail rebound to a new row, tiles surviving from the last one - and a ranked tile
    // with numerals or a landscape 16:9 still must not come back as a portrait poster. Encoding
    // the shape in the viewType keeps every tile the pool holds a tile built for this row's
    // exact shape, which is also what lets the rails keep a deeper pool without visual bugs.
    private val tileType: Int = (if (poster) 1 else 0) or (if (landscape) 2 else 0) or
        (if (ranked) 4 else 0) or (if (frame) 8 else 0) or (if (below) 16 else 0) or
        (if (tileSquare) 32 else 0) or (if (glass) 64 else 0) or (if (featured) 128 else 0) or
        (if (seeAll) 256 else 0)

    override fun getItemViewType(position: Int): Int = tileType
    // Continue is the one shelf a viewer can clean up in place: long-press removes the tile and
    // the hub marks the record dismissed (the position survives; pressing the title re-arms it).
    // The mutable copy exists so the removal animates without re-reading Home.
    private val dismissible = row.id == "continue"
    // My List is the mirror image: a held tile that leaves the list should leave the shelf too.
    private val myListRow = row.id == "mylist"
    private val shown: MutableList<Item> = row.items.toMutableList()

    override fun getItemCount(): Int = shown.size + if (seeAll) 1 else 0

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ShelfCardHolder {
        val card = FrameLayout(activity)
        card.layoutParams = RecyclerView.LayoutParams(cardW, cardH + captionH).apply {
            // Apple's shelves breathe; tiles never touch each other. Glass measures a
            // poster gap, the Kids contract a wider plate gap; both win over the classic 26.
            marginEnd = if (theme.token("spacing", "posterGap", 0.0) > 0.0)
                theme.space("posterGap", 26f) else theme.space("cardGap", 26f)
        }
        card.isFocusable = true
        card.isFocusableInTouchMode = true
        card.isClickable = true
        card.setBackgroundColor(if (frame) theme.surface else Color.TRANSPARENT)
        // This transparent, oversized view is the focus treatment's canvas. It must be the
        // first child so the artwork masks the centre of the halo; adding it last painted the
        // former focus bands over the poster and caption.
        val glowPad = theme.focusGlowInset
        val ringOn = View(activity)
        card.clipChildren = false
        card.addView(ringOn, FrameLayout.LayoutParams(cardW + glowPad * 2, cardH + glowPad * 2).apply {
            gravity = Gravity.TOP or Gravity.START
            leftMargin = -glowPad
            topMargin = -glowPad
        })
        // A pack whose below-captions name a plate colour (the Kids stage frames every tile in
        // its own yellow) gets chunky framed tiles: the photo sits INSIDE a rounded plate with
        // a soft glow, as in the reference, never pasted bare onto the sheet.
        val tilePlate = if (below) theme.colorOr("cardTint", 0) else 0
        val tileInset = if (tilePlate != 0) theme.space("kidsTileInset", 12f) else 0
        if (tilePlate != 0) {
            card.addView(View(activity).apply {
                background = Views.rounded(tilePlate,
                    if (poster && tileSquare) radius else radius + theme.px(10f))
                elevation = theme.px(10f).toFloat()
            }, FrameLayout.LayoutParams(cardW, cardH).apply { gravity = Gravity.TOP })
        }
        val art = ImageView(activity).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(theme.surface2)
            clipToOutline = true
            // The plate sits at elevation 10 to cast its glow; the photo must sit above it or
            // Android's z-order paints the plate over the artwork.
            if (tilePlate != 0) elevation = theme.px(12f).toFloat()
            // Glass tiles are bare art plates: the pack's depth cue is a real cast shadow
            // under the rounded outline, separate from the focus glow painted behind.
            if (glass) elevation = theme.space("posterElevation", 10f).toFloat()
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, v.width, v.height, theme.radius("card", 6f).toFloat())
                }
            }
        }
        card.addView(art, FrameLayout.LayoutParams(cardW - tileInset * 2, cardH - tileInset * 2).apply {
            gravity = Gravity.TOP
            leftMargin = tileInset
            topMargin = tileInset
        })
        if (!frame && !below) {
            val shade = View(activity).apply {
                background = GradientDrawable(
                    GradientDrawable.Orientation.BOTTOM_TOP,
                    intArrayOf(Color.argb(if (glass) 190 else 235, 0, 0, 0), Color.TRANSPARENT)
                )
                // Parents paint by Z: the elevated glass art would cover a Z-0 shade.
                if (glass) elevation = theme.space("posterElevation", 10f) + 2f
            }
            card.addView(shade, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, theme.px(140f)).apply {
                gravity = Gravity.BOTTOM
                bottomMargin = captionH
            })
        }
        if (frame) {
            // An editorial card is a printed plate: opaque caption bar, hairline border, art above.
            card.addView(View(activity).apply { setBackgroundColor(theme.surface) },
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, captionH).apply {
                    gravity = Gravity.BOTTOM
                })
            card.addView(View(activity).apply {
                background = Views.ring(theme.line, radius, theme.px(1f))
            }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        } else if (glass && !below) {
            // A glass tile that OVERLAYS its name gets a darker name strip welded to the art.
            // A below-caption pack must NOT get one: the strip would paint as a stray grey box
            // floating on the wall under every poster (owner ruling 2026-11-03 — the reference
            // rail caption is bare text on the wall; the tile itself is the plate).
            card.addView(View(activity).apply {
                // A console pack's glass is cool blue; a cinema pack can keep the neutral
                // dark plate. The card shape stays shared while colour remains declarative.
                background = Views.rounded(
                    theme.color("glassSurface", "rgba(8,10,14,0.59)"),
                    theme.radius("card", 6f),
                    theme.px(1f),
                    theme.color("glassLine", "rgba(255,255,255,0.35)"),
                )
            }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, theme.px(84f)).apply {
                gravity = Gravity.BOTTOM
                leftMargin = theme.px(8f); rightMargin = theme.px(8f); bottomMargin = theme.px(8f)
            })
        }
        val name = Views.text(app, "caption", if (below) 18f else 19f,
            theme.colorOr("captionInk", theme.ink), "", bold = true,
            maxLines = if (below) 1 else 2)
        if (poster && tileSquare) {
            // The Kids contract prints captions as letter-spaced capitals.
            name.isAllCaps = true
            name.letterSpacing = 0.12f
        }
        name.setPadding(theme.px(if (below) 4f else 12f), 0,
            theme.px(if (below) 4f else 12f), theme.px(if (below) 0f else 10f))
        card.addView(name, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.BOTTOM
            bottomMargin = when {
                below -> if (poster && tileSquare) theme.space("kidsCaptionGap", 16f)
                    else theme.space("captionNameBottom", 26f)
                frame -> theme.px(8f)
                else -> 0
            }
        })
        val sub = Views.text(app, "meta", 14f, theme.colorOr("captionSub", theme.ink3), "", maxLines = 1)
        sub.setPadding(theme.px(4f), 0, theme.px(4f), theme.px(4f))
        sub.visibility = if (below) View.VISIBLE else View.GONE
        card.addView(sub, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.BOTTOM
            bottomMargin = theme.px(6f)
        })
        val logo = ImageView(activity).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            visibility = View.GONE
        }
        card.addView(logo, FrameLayout.LayoutParams(cardW - theme.px(28f),
            if (landscape) theme.px(64f) else theme.px(76f)).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            bottomMargin = when {
                below -> captionH + theme.px(10f)
                frame -> theme.px(10f)
                else -> theme.px(8f)
            }
        })
        val badge = Views.text(app, "meta", 13f,
            if (glass) theme.color("match", "#EF9F42") else Color.WHITE, "", bold = true, maxLines = 1)
        badge.setPadding(theme.px(8f), theme.px(3f), theme.px(8f), theme.px(3f))
        badge.background = if (glass) {
            // The glass chip: the same deep-glass capsule the PIN keys and nav pill ride,
            // with the pack's hairline rim — labels sit ON the art, where the eye lands
            // first (owner ask, 2026-11-03).
            badge.setPadding(theme.px(12f), theme.px(4f), theme.px(12f), theme.px(4f))
            Views.rounded(theme.color("gatePlate", "rgba(8,16,28,0.88)"), theme.px(999f),
                theme.px(1f), theme.color("glassRim", "rgba(255,255,255,0.38)"))
        } else Views.rounded(Color.argb(185, 8, 8, 8), theme.px(5f))
        card.addView(badge, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.START
            leftMargin = theme.px(10f); topMargin = theme.px(10f)
        })
        val num = Views.text(app, "h1", 0f, Color.WHITE, "", bold = true, gravity = Gravity.CENTER)
        num.visibility = if (ranked) View.VISIBLE else View.GONE
        card.addView(num, FrameLayout.LayoutParams(theme.px(110f), ViewGroup.LayoutParams.MATCH_PARENT).apply {
            gravity = Gravity.START
        })
        // The See all tile's badge: a feather chevron on a quiet disc, hidden on every other tile.
        val chevron = ImageView(activity).apply {
            setImageResource(R.drawable.ic_chevron_right)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(theme.px(20f), theme.px(20f), theme.px(20f), theme.px(20f))
            if (glass) {
                // On a frosted plate the disc is itself frost, with light ink.
                setColorFilter(theme.color("inkOnWall", "#FFFFFF"), android.graphics.PorterDuff.Mode.SRC_IN)
                background = Views.rounded(theme.color("discGlass", "rgba(255,255,255,0.16)"), theme.px(999f))
            } else {
                setColorFilter(theme.ink, android.graphics.PorterDuff.Mode.SRC_IN)
                background = Views.rounded(theme.surface2, theme.px(999f))
            }
            visibility = View.GONE
        }
        card.addView(chevron, FrameLayout.LayoutParams(theme.px(78f), theme.px(78f)).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            topMargin = (cardH - theme.px(78f)) / 2 - theme.px(24f)
        })
        // IMDb rating and the certificate ride the top-right corner, gold star and plain badge,
        // exactly as the pack's card design drew them.
        val imdb = Views.text(app, "meta", 13f, Color.parseColor("#F6C621"), "", bold = true, maxLines = 1)
        val cert = Views.text(app, "meta", 13f, Color.WHITE, "", bold = true, maxLines = 1)
        if (glass) {
            // Same deep-glass capsule as the NEW/HOT chip: the rating rides ON the art,
            // where the eye lands first (owner ask, 2026-11-03).
            imdb.setPadding(theme.px(12f), theme.px(4f), theme.px(12f), theme.px(4f))
            cert.setPadding(theme.px(12f), theme.px(4f), theme.px(12f), theme.px(4f))
            imdb.background = Views.rounded(theme.color("gatePlate", "rgba(8,16,28,0.88)"),
                theme.px(999f), theme.px(1f), theme.color("glassRim", "rgba(255,255,255,0.38)"))
            cert.background = imdb.background
        } else {
            imdb.setPadding(theme.px(8f), theme.px(3f), theme.px(8f), theme.px(3f))
            imdb.background = Views.rounded(Color.argb(185, 8, 8, 8), theme.px(5f))
            cert.setPadding(theme.px(8f), theme.px(3f), theme.px(8f), theme.px(3f))
            cert.background = Views.rounded(Color.argb(185, 8, 8, 8), theme.px(5f))
        }
        val corner = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
        }
        corner.addView(imdb)
        corner.addView(cert, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { marginStart = theme.px(6f) })
        card.addView(corner, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            rightMargin = theme.px(10f); topMargin = theme.px(10f)
        })
        val progress = android.widget.ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            // The prior bar was the old Netflix red hard-code.  In the Apple pack this is a
            // quiet white playhead over a translucent track, matching the neutral controls.
            val track = GradientDrawable().apply {
                setColor(Color.argb(84, 255, 255, 255))
                cornerRadius = theme.px(2f).toFloat()
            }
            val fill = ClipDrawable(GradientDrawable().apply {
                setColor(Color.argb(235, 255, 255, 255))
                cornerRadius = theme.px(2f).toFloat()
            }, Gravity.LEFT, ClipDrawable.HORIZONTAL)
            progressDrawable = LayerDrawable(arrayOf(track, fill)).apply {
                setId(0, android.R.id.background)
                setId(1, android.R.id.progress)
            }
            visibility = View.GONE
        }
        card.addView(progress, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, theme.px(4f)).apply {
            gravity = Gravity.BOTTOM
            bottomMargin = captionH
        })
        val slideState = if (featured) View(activity).apply {
            // Featured tiles are previews of the hero, not a second focus target. Apple gets
            // one short active-slide marker; other packs retain their existing full rim.
            background = if (theme.packString("featuredMarker", "rim") == "bar") {
                // A black cradle keeps the white marker legible even over a bright poster;
                // this is state, not another card outline, so it cannot be mistaken for focus.
                LayerDrawable(arrayOf(
                    Views.rounded(Color.argb(205, 0, 0, 0), theme.px(999f)),
                    Views.rounded(Color.WHITE, theme.px(999f)),
                )).apply {
                    val side = theme.px(11f)
                    setLayerInset(1, side, theme.px(3f), side, theme.px(3f))
                }
            } else {
                Views.ring(Color.argb(140, 255, 255, 255), radius, theme.px(2f))
            }
            visibility = View.GONE
        } else null
        if (slideState != null) {
            val markerLayout = if (theme.packString("featuredMarker", "rim") == "bar") {
                FrameLayout.LayoutParams(theme.px(84f), theme.px(10f)).apply {
                    gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                    bottomMargin = captionH + theme.px(10f)
                }
            } else {
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, cardH).apply {
                    gravity = Gravity.TOP
                }
            }
            card.addView(slideState, markerLayout)
        }
        if (tilePlate != 0) {
            // On a framed tile the photo itself rides above the opaque plate; every overlay —
            // badge, rating corner, rank numeral, genre label, logo, playhead — must ride above
            // the photo or Android's z-order buries it under the plate (owner round 5: category
            // tiles rendered blank, shelf badges never showed).
            for (v in listOf(badge, corner, num, chevron, logo, progress))
                v.elevation = theme.px(14f).toFloat()
        }
        val holder = ShelfCardHolder(card, art, name, sub, logo, badge, num, progress, slideState, imdb, cert, corner, chevron)
        if (glass) {
            // Parents paint by Z: the glass art rides posterElevation for its cast shadow, so
            // anything that stands ON the art (chips, rank column, playhead, see-all chevron)
            // needs its own step of elevation or the elevated art simply paints over it —
            // that is why the pass-13 rating/NEW chips were invisible at first.
            val overlayZ = theme.space("posterElevation", 10f) + 2f
            logo.elevation = overlayZ
            badge.elevation = overlayZ
            num.elevation = overlayZ
            progress.elevation = overlayZ
            chevron.elevation = overlayZ
            corner.elevation = overlayZ
        }
        // Glass tiles lift on focus exactly like the hero plates (halo + elevation on the
        // same resting cast shadow); other packs keep the flat, perf-first treatment.
        Focus.attach(card, ringOn, app, radius, animateScale = false, elevate = glass,
            glowInset = glowPad,
            // The hero's rail is a loop, not a corridor: walking off either end lands on the
            // other, the way the prototype's tiles wrap and the billboard never dead-ends.
            arrow = if (featured) { code ->
                when (code) {
                    android.view.KeyEvent.KEYCODE_DPAD_RIGHT ->
                        if (holder.bindingAdapterPosition >= itemCount - 1) { host.focusFeatured(0); true } else false
                    android.view.KeyEvent.KEYCODE_DPAD_LEFT ->
                        if (holder.bindingAdapterPosition == 0) { host.focusFeatured(itemCount - 1); true } else false
                    else -> false
                }
            } else null,
            onFocus = { focused ->
                if (!focused) return@attach
                if (featured) host.onSlideFocused(holder.bindingAdapterPosition)
                else holder.item?.let { host.onCardFocused(card, it) }
            })
        // The focused tile wears a slab of the pack's glass over its own artwork: the poster bends,
        // splits colour and lights its rim while the card holds focus. The slab is invisible on every
        // unfocused tile, so only one lens is ever compositing.
        GlassKit.onGlassFocus(card, art, app, radius)
        return holder
    }

    override fun onBindViewHolder(holder: ShelfCardHolder, position: Int) {
        if (seeAll && position >= shown.size) {
            bindSeeAll(holder)
            return
        }
        val item = shown[position]
        holder.item = item
        holder.itemView.contentDescription = CardActions.announcement(
            app, profileId, item, itemAnnouncement(item),
            holdHint = "holding opens its options",
        )
        if (item.type == "genre") {
            bindGenre(holder, item)
            return
        }
        // Hold OK on a card asks first: the hold opens the choice sheet and a second OK on a
        // row is the confirmation. Acting on the hold itself dropped Continue tiles the instant
        // a viewer rested a thumb on one (owner report, 2026-11-03). Every theme walks this one
        // adapter, so the gesture and its wording belong to the app rather than to a pack.
        holder.itemView.isLongClickable = item.type != "genre"
        holder.itemView.setOnLongClickListener {
            if (item.isLive() || item.type == "genre") return@setOnLongClickListener false
            val at = holder.bindingAdapterPosition
            if (at !in shown.indices) return@setOnLongClickListener false
            CardActions.holdSheet(
                activity, app, api, profileId, item,
                canDismissContinue = dismissible,
                rowId = if (host.managesRows()) row.id else null,
                rowTitle = row.title,
                afterList = { onList ->
                    val now = holder.bindingAdapterPosition
                    if (!onList && myListRow && now in shown.indices) {
                        shown.removeAt(now)
                        notifyItemRemoved(now)
                        notifyItemRangeChanged(now, shown.size - now)
                    }
                    holder.itemView.contentDescription =
                        CardActions.announcement(app, profileId, shown.getOrNull(now) ?: item,
                            itemAnnouncement(item))
                },
                afterDismiss = {
                    val now = holder.bindingAdapterPosition
                    if (now in shown.indices) {
                        shown.removeAt(now)
                        notifyItemRemoved(now)
                        notifyItemRangeChanged(now, shown.size - now)
                    }
                },
                afterHideRow = { host.onRowHidden(row.id) },
            )
            true
        }
        holder.chevron.visibility = View.GONE
        if (featured) holder.itemView.nextFocusUpId = host.navAnchorId()
        // A square framed tile (the Kids stage) wears its facts ON the art like every other
        // card pack — a NEW pill and the rating corner — while its name stays printed below
        // (owner round 5). Scene packs still keep the meta line under the artwork instead.
        val onTileFacts = below && poster && tileSquare
        holder.name.text = item.title
        holder.name.visibility = View.VISIBLE
        holder.sub.visibility = if (below && !onTileFacts) View.VISIBLE else View.GONE
        if (below && !onTileFacts) {
            if (theme.packString("captionMeta", "") == "dot") {
                // The glass reference prints "● 7.8 · 2023": an accent dot welded to the
                // rating, the year quieter behind it. One shared format for the whole pack.
                // With the rating and NEW/HOT chips moved ON the art (owner ask, 2026-11-03)
                // the glass caption keeps only the year — repeating the facts under the tile
                // is how they got buried in the first place.
                holder.sub.text = if (glass)
                    (if (item.year > 0) item.year.toString() else "")
                else captionDotLine(item)
            } else {
                val sub = ArrayList<String>()
                // Scene cards put their facts below the artwork; overlay and glass cards use the
                // on-art badge/corner treatment instead. This is a card-layout decision, not a
                // named-theme exception, so any future spacious pack inherits it cleanly.
                if (ranked && below) sub.add("#${position + 1}")
                if (below && item.isNew) sub.add("New")
                if (item.year > 0) sub.add(item.year.toString())
                if (item.match > 0) sub.add("${item.match}% match")
                if (below && item.rating > 0) {
                    sub.add(String.format(java.util.Locale.US, "★ %.1f", item.rating))
                }
                if (below && item.maturity.isNotBlank()) sub.add(item.maturity)
                item.genres.firstOrNull()?.takeIf { it.isNotBlank() }?.let { sub.add(it) }
                holder.sub.text = sub.joinToString(" · ")
            }
        }
        holder.logo.visibility = View.GONE
        holder.logo.tag = item.identity + ":logo"
        val imdbR = if (item.rating > 0) String.format(java.util.Locale.US, "★ %.1f", item.rating) else ""
        // The glass pack moved its facts ON the art (owner ask, 2026-11-03): a rating chip
        // top-right and a NEW/HOT chip top-left, deep-glass capsules over the poster — the
        // reference's own tiles carry no text at all, so on-art chips beat a caption line the
        // eye never reaches while scanning. Scene lockups still keep metadata under the image.
        val onArt = !below || glass || onTileFacts
        if (onArt) {
            holder.imdb.text = imdbR
            holder.imdb.visibility = if (imdbR.isNotEmpty()) View.VISIBLE else View.GONE
            holder.cert.text = item.maturity
            holder.cert.visibility = if (item.maturity.isNotBlank()) View.VISIBLE else View.GONE
            holder.corner.visibility =
                if (imdbR.isNotEmpty() || item.maturity.isNotBlank()) View.VISIBLE else View.GONE
        } else {
            holder.imdb.visibility = View.GONE
            holder.cert.visibility = View.GONE
            holder.corner.visibility = View.GONE
        }
        val facts = ArrayList<String>()
        if (!below || onTileFacts) {
            if (item.isNew) facts.add("NEW")
            if (item.match > 0) facts.add("${item.match}% match")
        } else if (glass) {
            if (item.isNew) facts.add("NEW")
            // The hub's match is how strongly a title fits this house; at 90+ it IS the hot
            // signal, and it reads as one word instead of a percentage on a small chip.
            if (item.match >= 90) facts.add("HOT")
        }
        holder.badge.text = facts.joinToString(" · ")
        holder.badge.visibility = if (facts.isEmpty()) View.GONE else View.VISIBLE
        val artUrl = if (landscape) item.backdrop ?: item.poster else item.poster ?: item.backdrop
        val logoUrl = item.logo
        // Posters first, logos after: the queue is newest-first, so enqueueing a logo right
        // after its poster ran the decoration ahead of the art. Title logos belong on WIDE tiles
        // (owner ruling): a plain poster is already a title lockup, and the glass pass-12
        // exception for portrait posters is retired (2026-11-03) — the hub's poster art already
        // composes its own lockup, so the overlay double-painted it.
        BrowseImages.load(holder.art, artUrl, cardW) {
            // A wide tile carries the title logo; a poster already has its own lockup. The one
            // portrait exception is a tile with no artwork at all — a logo there names a plate
            // that would otherwise be empty.
            if (logoUrl.isNullOrBlank()) return@load
            if (!landscape && !artUrl.isNullOrBlank()) return@load
            holder.logo.visibility = View.VISIBLE
            BrowseImages.load(holder.logo, logoUrl, cardW) { ok ->
                holder.logo.visibility = if (ok) View.VISIBLE else View.GONE
                // A scene card keeps its name under the art even when the art carries a logo.
                holder.name.visibility = if (ok && !below) View.GONE else View.VISIBLE
            }
        }
        if (ranked && !below) {
            // A recycled tile may have worn the See all lockup; the rank column rides the left edge.
            holder.num.visibility = View.VISIBLE
            holder.num.setPadding(0, 0, 0, 0)
            holder.num.layoutParams = FrameLayout.LayoutParams(theme.px(110f), ViewGroup.LayoutParams.MATCH_PARENT).apply {
                gravity = Gravity.START
            }
            holder.num.text = (position + 1).toString()
            holder.num.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, cardH * 0.44f)
            holder.num.setShadowLayer(theme.px(6f).toFloat(), 0f, 0f, Color.BLACK)
            if (theme.packString("rank", "solid") == "outline") {
                // Apple's board numerals are hollow, not filled: the art stays visible through them.
                holder.num.setTextColor(Color.WHITE)
                holder.num.paint.style = android.graphics.Paint.Style.STROKE
                holder.num.paint.strokeWidth = theme.px(3f).toFloat()
            } else {
                holder.num.setTextColor(Color.WHITE)
                holder.num.paint.style = android.graphics.Paint.Style.FILL
            }
        } else {
            holder.num.visibility = View.GONE
            // Reset mutable text paint in case this holder later serves a genre or See all tile.
            holder.num.paint.style = android.graphics.Paint.Style.FILL
            holder.num.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
        }
        val p = item.progress
        if (p != null && p.watchedMs > 0L) {
            holder.progress.visibility = View.VISIBLE
            holder.progress.progress = ((p.positionMs.toFloat() / p.watchedMs.toFloat()).coerceIn(0f, 1f) * 1000).toInt()
        } else {
            holder.progress.visibility = View.GONE
        }
        // The marker is state, not focus: it stays on the preview belonging to the hero while
        // the visible keyline/glow belongs only to the real D-pad target.
        holder.slideState?.visibility = if (position == host.heroPos()) {
            View.VISIBLE
        } else {
            View.GONE
        }
        holder.itemView.tag = null
        holder.itemView.setOnClickListener {
            if (item.isLive()) host.openLive(item) else host.openDetail(item)
        }
    }

    /**
     * Poster art is intentionally not announced: the card already has a short, useful label.
     * This keeps TalkBack's D-pad walk aligned with the visual hierarchy instead of reading a
     * title, badge, artwork and caption as four separate stops.
     */
    private fun itemAnnouncement(item: Item): String = buildString {
        append(item.title)
        if (item.year > 0) append(", ").append(item.year)
        if (item.match > 0) append(", ").append(item.match).append("% match")
        if (item.isLive()) append(", live")
        append(", open details")
    }

    /** The shelf's last tile: a quiet bordered card with a chevron disc and the words "See all". */
    private fun captionDotLine(item: Item): CharSequence {
        val theme = app.theme
        val sb = android.text.SpannableStringBuilder()
        val rating = when {
            item.rating > 0 -> String.format(java.util.Locale.US, "%.1f", item.rating)
            item.match > 0 -> "${item.match}%"
            else -> null
        }
        if (rating != null) {
            sb.append("●")
            sb.setSpan(android.text.style.ForegroundColorSpan(theme.color("match", "#EF9F42")),
                0, 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.append(" ").append(rating)
            sb.setSpan(android.text.style.ForegroundColorSpan(theme.color("captionInk", "#EAF1F6")),
                2, sb.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (item.year > 0) sb.append("   ").append(item.year.toString())
        } else if (item.year > 0) {
            sb.append(item.year.toString())
        }
        if (item.isNew) {
            if (sb.isNotEmpty()) sb.append("   ")
            sb.append("New")
            sb.setSpan(android.text.style.ForegroundColorSpan(theme.color("match", "#EF9F42")),
                sb.length - 3, sb.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return sb
    }

    private fun bindSeeAll(holder: ShelfCardHolder) {
        holder.item = null
        holder.itemView.tag = "see-all"
        holder.itemView.contentDescription = "See all ${row.title}"
        // Cancel any in-flight poster/logo decode from the card this view used to hold; a
        // callback that still matches the old URL would paint over the See all lockup.
        holder.art.tag = "see-all:" + row.id
        holder.logo.tag = "see-all:" + row.id
        holder.art.setImageDrawable(null)
        holder.art.setBackgroundColor(Color.TRANSPARENT)
        // Glass: a frosted translucent plate on the wall (fill + rim + sheen + cast shadow),
        // not an opaque paper card — other packs keep their bordered sheet.
        holder.art.background = if (glass) GlassKit.tilePlate(app, theme.radius("card", 6f),
            theme.color("seeAllGlassA", "rgba(232,240,248,0.16)"),
            theme.color("seeAllGlassB", "rgba(196,210,224,0.08)"))
        else Views.rounded(theme.surface, theme.radius("card", 6f), theme.px(1f), theme.line)
        holder.name.text = row.title
        holder.name.visibility = View.VISIBLE
        holder.sub.visibility = View.GONE
        holder.logo.visibility = View.GONE
        holder.corner.visibility = View.GONE
        holder.badge.visibility = View.GONE
        holder.progress.visibility = View.GONE
        holder.slideState?.visibility = View.GONE
        holder.chevron.visibility = View.VISIBLE
        holder.num.visibility = View.VISIBLE
        holder.num.setPadding(0, 0, 0, 0)
        holder.num.text = "See all"
        holder.num.setTextColor(if (glass) theme.color("inkOnWall", "#FFFFFF") else theme.ink2)
        holder.num.gravity = Gravity.CENTER
        holder.num.paint.style = android.graphics.Paint.Style.FILL
        holder.num.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
        holder.num.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, theme.px(21f).toFloat())
        holder.num.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            topMargin = (cardH - theme.px(78f)) / 2 + theme.px(64f)
        }
        holder.itemView.setOnClickListener { host.openRowFull(row) }
    }

    /**
     * A genre has no poster of its own — the hub sends null art for these tiles, and an empty
     * plate with a badge read as a broken card. So the tile IS the artwork: a diagonal colour
     * wash keyed to the genre's name (every shelf shows the same tile in the same colours),
     * with the name set large inside it, as Apple TV's category tiles are.
     */
    private fun bindGenre(holder: ShelfCardHolder, item: Item) {
        holder.itemView.tag = "genre"
        holder.itemView.contentDescription = "Browse ${item.title}"
        var hash = 0
        for (c in item.title.lowercase()) hash = (hash * 31 + c.code) % 360
        val hue = hash.toFloat()
        // The tile is a wash, not a poster. Retag the recycled views first so a late image
        // callback for the card they used to hold cannot show through the colour plate.
        holder.art.tag = "genre:" + item.identity
        holder.logo.tag = "genre:" + item.identity
        holder.art.setImageDrawable(null)
        holder.art.setBackgroundColor(Color.TRANSPARENT)
        // The Glass pack never prints an opaque plate: the genre's own colour wash sits at
        // translucent alpha over the wall with the pack's rim + sheen, and the tile's
        // elevation casts its shadow — same three material layers as every Glass surface.
        holder.art.background = if (glass) GlassKit.tilePlate(app, theme.radius("card", 6f),
            Color.HSVToColor(0xA8, floatArrayOf(hue, 0.55f, 0.78f)),
            Color.HSVToColor(0x88, floatArrayOf((hue + 42f) % 360f, 0.80f, 0.42f)))
        else GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(
                Color.HSVToColor(floatArrayOf(hue, 0.55f, 0.78f)),
                Color.HSVToColor(floatArrayOf((hue + 42f) % 360f, 0.80f, 0.42f)),
            ),
        )
        holder.name.visibility = View.GONE
        holder.sub.visibility = View.GONE
        holder.logo.visibility = View.GONE
        holder.badge.visibility = View.GONE
        holder.corner.visibility = View.GONE
        holder.progress.visibility = View.GONE
        holder.slideState?.visibility = View.GONE
        holder.chevron.visibility = View.GONE
        holder.num.visibility = View.VISIBLE
        holder.num.text = item.title
        holder.num.setTextColor(Color.WHITE)
        holder.num.gravity = Gravity.CENTER
        holder.num.maxLines = 2
        holder.num.setPadding(theme.px(16f), 0, theme.px(16f), 0)
        holder.num.paint.style = android.graphics.Paint.Style.FILL
        holder.num.setShadowLayer(theme.px(10f).toFloat(), 0f, theme.px(2f).toFloat(), Color.argb(160, 0, 0, 0))
        holder.num.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, cardH * 0.22f)
        holder.num.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, cardH
        ).apply { gravity = Gravity.TOP }
        // A genre tile opens the genre's own full list, not a title-word search for "crime".
        // A catalogue tile (from the Browse addons row) opens that addon's catalogue instead.
        holder.itemView.setOnClickListener {
            val intent = Intent(activity, SectionActivity::class.java)
                .putExtra(SectionActivity.EXTRA_PROFILE, profileId)
                .putExtra(SectionActivity.EXTRA_TITLE, item.title)
            if (item.identity.startsWith("catalog:")) {
                val rest = item.identity.removePrefix("catalog:")
                intent.putExtra(SectionActivity.EXTRA_ADDON, rest.substringBefore(':'))
                intent.putExtra(SectionActivity.EXTRA_CATALOG, rest.substringAfter(':', ""))
            } else {
                intent.putExtra(SectionActivity.EXTRA_GENRE, item.title)
            }
            activity.startActivity(intent)
        }
    }
}


class ShelfCardHolder(
    view: View, val art: ImageView, val name: TextView, val sub: TextView, val logo: ImageView,
    val badge: TextView, val num: TextView, val progress: android.widget.ProgressBar,
    val slideState: View?, val imdb: TextView, val cert: TextView, val corner: View,
    val chevron: ImageView,
) : RecyclerView.ViewHolder(view) {
    var item: Item? = null
}


