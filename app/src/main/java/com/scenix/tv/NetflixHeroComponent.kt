package com.scenix.tv

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.scenix.tv.core.AppContext
import com.scenix.tv.core.Focus
import com.scenix.tv.core.Item
import com.scenix.tv.core.Views

/**
 * The Cinema Row home billboard.
 *
 * This deliberately is not an Apple HeroComponent with a different paint job.  Netflix-style
 * browsing gives the title one wide, content-first billboard and lets the viewer move that
 * recommendation directly with LEFT/RIGHT.  There is no thumbnail-pagination rail under it:
 * shelves begin immediately below the billboard and retain their own browsing role.
 *
 * BrowseActivity owns the page/section routes and supplies [Callbacks].  Keeping the entire
 * visual family here makes a `homeLayout: "netflix"` pack an actual layout change, rather than a
 * growing list of `theme.id` conditionals in the host activity.
 */
internal class NetflixHeroComponent(
    context: Context,
    private val app: AppContext,
    private val callbacks: Callbacks,
) : FrameLayout(context) {

    data class Callbacks(
        /** Primary action for the visible title (currently routes to its native detail surface). */
        val onPlay: (Item) -> Unit,
        /** Secondary, explicitly labelled path to the title detail surface. */
        val onMoreInfo: (Item) -> Unit,
        /** The host advances the shared slide index; this component never owns duplicate state. */
        val onSlideRequested: (Int) -> Unit,
        /** Pause ambient work when the viewer is actively operating the billboard. */
        val onHeroFocused: () -> Unit,
    )

    private val theme = app.theme
    private var current: Item? = null
    private var slideIndex = 0
    private var slideCount = 0
    private var renderGeneration = 0L

    /** Public boundary used by Browse's directional router. */
    val root: View get() = this
    /** First deterministic stop when the page enters the Netflix billboard. */
    val primaryControl: TextView
    /** A visible secondary action avoids hiding all title context behind Play. */
    val infoControl: TextView
    /** Netflix uses direct hero stepping instead of a preview-selector rail. */
    val selectorRoot: View? get() = null
    val preferredHeightPx: Int get() = theme.heroLayout.heightPx.coerceAtLeast(theme.px(570f))

    private val art: ImageView
    private val logo: ImageView
    private val title: TextView
    private val meta: TextView
    private val position: TextView

    init {
        clipChildren = false
        clipToPadding = false
        setBackgroundColor(theme.bg)

        art = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(theme.surface2)
        }
        addView(art, LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        ))

        // The left reading scrim and low shelf scrim deliberately leave the right image visible.
        // It is a cinematic billboard, not a generic glass card, and uses only one composited
        // overlay per axis so low-end TV GPUs do not pay for stacked translucent views.
        addView(View(context).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(
                    Color.argb(245, 0, 0, 0),
                    Color.argb(190, 0, 0, 0),
                    Color.argb(36, 0, 0, 0),
                    Color.TRANSPARENT,
                ),
            )
        }, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        addView(View(context).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.BOTTOM_TOP,
                intArrayOf(Color.argb(238, 0, 0, 0), Color.TRANSPARENT),
            )
        }, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val words = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.BOTTOM or Gravity.START
            setPadding(theme.gutter, theme.px(54f), theme.gutter, theme.px(44f))
        }
        logo = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_START
            visibility = View.GONE
        }
        title = Views.text(app, "h1", 66f, theme.ink, "", bold = true, maxLines = 2).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, theme.type("h1", 66f) * 0.88f)
        }
        meta = Views.text(app, "sub", 21f, theme.color("ink1", "#E5E5E5"), "", maxLines = 1)
        val actions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
        }
        primaryControl = actionButton("Play", primary = true)
        infoControl = actionButton("More Info", primary = false)
        actions.addView(primaryControl, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, theme.px(64f),
        ))
        actions.addView(infoControl, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, theme.px(64f),
        ).apply { marginStart = theme.px(14f) })
        words.addView(logo, LinearLayout.LayoutParams(theme.px(520f), theme.px(112f)).apply {
            bottomMargin = theme.px(4f)
        })
        words.addView(title, LinearLayout.LayoutParams(theme.px(760f), ViewGroup.LayoutParams.WRAP_CONTENT))
        words.addView(meta, LinearLayout.LayoutParams(theme.px(760f), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = theme.px(14f)
        })
        words.addView(actions, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, theme.px(64f),
        ).apply { topMargin = theme.px(24f) })
        addView(words, LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        ))

        // The position is reading context, not a second interactive pagination UI.  The visible
        // highlight remains on Play/More Info while arrows replace the one feature title.
        position = Views.text(app, "meta", 16f, theme.ink2, "", gravity = Gravity.END)
        addView(position, LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            gravity = Gravity.END or Gravity.BOTTOM
            rightMargin = theme.gutter
            bottomMargin = theme.px(46f)
        })

        primaryControl.nextFocusRightId = infoControl.id
        infoControl.nextFocusLeftId = primaryControl.id
        primaryControl.setOnClickListener { current?.let(callbacks.onPlay) }
        infoControl.setOnClickListener { current?.let(callbacks.onMoreInfo) }
    }

    /** Render the title selected by the host's one true slide index. */
    fun render(item: Item, activeIndex: Int, totalSlides: Int) {
        current = item
        slideIndex = activeIndex
        slideCount = totalSlides
        val generation = ++renderGeneration
        title.text = item.title
        title.visibility = View.VISIBLE
        logo.visibility = View.GONE
        logo.setImageDrawable(null)
        primaryControl.contentDescription = "Play ${item.title}"
        infoControl.contentDescription = "More information about ${item.title}"

        val facts = ArrayList<String>()
        if (item.match > 0) facts += "${item.match}% Match"
        if (item.isNew) facts += "New"
        if (item.year > 0) facts += item.year.toString()
        if (item.maturity.isNotBlank()) facts += item.maturity
        item.genres.firstOrNull()?.takeIf { it.isNotBlank() }?.let(facts::add)
        meta.text = facts.joinToString("  •  ")
        position.text = if (totalSlides > 1) "${activeIndex + 1} / $totalSlides" else ""

        // One low-resolution backdrop decode is enough for a 1080p ambient crop.  Card art stays
        // lazy in its RecyclerView rail, which keeps this structural variant as cheap as Home.
        BrowseImages.load(art, item.backdrop ?: item.poster, theme.px(1120f))
        if (!item.logo.isNullOrBlank()) {
            logo.visibility = View.VISIBLE
            BrowseImages.load(logo, item.logo, theme.px(520f)) { ok ->
                if (generation != renderGeneration) return@load
                logo.visibility = if (ok) View.VISIBLE else View.GONE
                title.visibility = if (ok) View.GONE else View.VISIBLE
            }
        }
    }

    fun setNextFocusUp(id: Int) {
        primaryControl.nextFocusUpId = id
        infoControl.nextFocusUpId = id
    }

    fun focusPrimary(): Boolean = primaryControl.requestFocus()

    private fun actionButton(label: String, primary: Boolean): TextView {
        val button = Views.text(
            app,
            "button",
            22f,
            if (primary) Color.BLACK else theme.ink,
            label,
            bold = true,
            gravity = Gravity.CENTER,
        ).apply {
            id = View.generateViewId()
            isFocusable = true
            isClickable = true
            setPadding(theme.px(28f), 0, theme.px(28f), 0)
            background = if (primary) {
                Views.rounded(Color.WHITE, theme.radius("button", 4f))
            } else {
                Views.rounded(Color.argb(118, 45, 45, 45), theme.radius("button", 4f), theme.px(1f), theme.ink2)
            }
        }
        Focus.attach(
            button,
            button,
            app,
            theme.radius("button", 4f),
            scale = false,
            elevate = false,
            onFocus = { focused ->
                if (focused) callbacks.onHeroFocused()
            },
            arrow = { code ->
                // Primary and secondary controls keep their horizontal relationship.  Only the
                // outside edges advance the billboard, so one arrow always means one thing.
                when {
                    code == KeyEvent.KEYCODE_DPAD_LEFT && button === primaryControl -> {
                        requestSlide(-1); true
                    }
                    code == KeyEvent.KEYCODE_DPAD_RIGHT && button === infoControl -> {
                        requestSlide(1); true
                    }
                    else -> false
                }
            },
        )
        return button
    }

    private fun requestSlide(delta: Int) {
        if (slideCount < 2) return
        callbacks.onSlideRequested(delta)
    }
}
