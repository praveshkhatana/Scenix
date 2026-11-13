package com.scenix.tv.core

import android.app.Activity
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.scenix.tv.R

internal class TopNav(
    private val activity: Activity,
    private val app: AppContext,
    private val profileId: String,
    private val section: String,
    private val transparent: Boolean,
    private val shelfListId: Int,
    private val showsGames: Boolean,
    private val showsLive: Boolean,
    private val profileView: View,
    private val navStyle: String? = null,
    private val callbacks: Callbacks,
) : FrameLayout(activity) {

    interface Callbacks {
        fun goTab(route: String)
        fun onGames()
        fun onLive()
        fun onSearch()
        fun onSettings()
        fun onProfile()
        fun onChromeFocused()
    }

    var anchor: View? = null
        private set
    var group: ViewGroup? = null
        private set
    var stops: List<View> = emptyList()
        private set
    var liveStop: View? = null
        private set

    init {
        addView(buildBar(), LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
    }

    private fun buildBar(): View {
    val theme = app.theme
    // The owner's SCENIX-Logo.svg, not styled text: the letterforms must not change with the
    // font the television happens to ship.
    val brand = ImageView(activity).apply {
        setImageResource(R.drawable.scenix_wordmark)
        // The mark is drawn from the owner's SVG in its brand red, then tinted from the active
        // pack: Apple's is off-white, Netflix's red, PS5's blue. A fixed red wordmark on a
        // white/blue pack read as a borrowed Netflix logo.
        setColorFilter(theme.brand)
        scaleType = ImageView.ScaleType.FIT_START
        contentDescription = "Scenix"
    }
    val profile = navButton("Profile") {
        callbacks.onProfile()
    }
    val search = navButton("Search", theme.px(24f)) {
        callbacks.onSearch()
    }
    val settings = navButton("Settings", theme.px(28f)) {
        callbacks.onSettings()
    }
    if ((navStyle ?: theme.navStyle) == "pill") {
        // Apple-style packs float one centred pill over the stage; the wordmark stays at the edge.
        // The pill carries the household and the sections this app can play, in the order the web
        // bar teaches: face, Home, Movies, Series, Games, Live TV, then the actions.
        val bar = FrameLayout(activity)
        bar.setBackgroundColor(if (transparent) Color.TRANSPARENT else theme.bg)
        bar.addView(brand, FrameLayout.LayoutParams(
            theme.px(80f), theme.px(28f)
        ).apply {
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            leftMargin = theme.gutter
        })
        val pill = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(theme.px(10f), theme.px(6f), theme.px(10f), theme.px(6f))
            background = Views.rounded(theme.surface2, theme.px(999f), theme.px(1f), theme.line)
        }
        val pillProfile = profileView.apply { id = View.generateViewId() }
        pill.addView(pillProfile)
        val homeTab = navTab("Home", active = section.isBlank()) { callbacks.goTab("") }.apply {
            id = View.generateViewId()
        }
        val moviesTab = navTab("Movies", active = section == "movies") { callbacks.goTab("movies") }.apply {
            id = View.generateViewId()
        }
        val seriesTab = navTab("Series", active = section == "series") { callbacks.goTab("series") }.apply {
            id = View.generateViewId()
        }
        pill.addView(homeTab)
        pill.addView(moviesTab)
        pill.addView(seriesTab)
        // Games is its own mode, not another catalogue tab: the hub answers a games shelf set
        // that has nothing in common with movie rows, so the tab opens its own screen. It is a
        // door to an add-on, so a household without the games add-on gets no tab.
        // The host owns the add-on facts.
        val gamesTab = navTab("Games", active = false) {
            callbacks.onGames()
        }.apply { id = View.generateViewId() }
        gamesTab.visibility = if (showsGames) View.VISIBLE else View.GONE
        pill.addView(gamesTab)
        val live = navTab("Live TV") {
            callbacks.onLive()
        }
        // Live TV earns its place in the pill from the add-on and its playlist, not from a
        // guessed row. The answer outlives a tab switch so the bar never changes shape mid-walk.
        live.visibility = if (showsLive) View.VISIBLE else View.GONE
        liveStop = live
        live.id = View.generateViewId()
        pill.addView(live)
        pill.addView(View(activity).apply { setBackgroundColor(theme.line) },
            LinearLayout.LayoutParams(theme.px(1f), theme.px(26f)).apply {
                marginStart = theme.px(10f); marginEnd = theme.px(4f)
            })
        // Search and Settings ride as icons in the pill, as Apple's bar and the prototype draw them.
        val searchIcon = navIcon(R.drawable.ic_nav_search, "Search") {
            callbacks.onSearch()
        }.apply { id = View.generateViewId() }
        pill.addView(searchIcon)
        val gear = navIcon(R.drawable.ic_nav_settings, "Settings") {
            callbacks.onSettings()
        }
        gear.id = View.generateViewId()
        // UP from the top row reaches the pill at the tab you are standing on, not at the far
        // end of the bar — walking in should land where you already are.
        anchor = when (section) {
            "movies" -> moviesTab
            "series" -> seriesTab
            else -> homeTab
        }
        group = pill
        pill.addView(gear)
        // Focus search treats the 1px divider and the full-screen stage as valid geometry.
        // The result was that RIGHT at the end of the tabs could fall into a shelf instead of
        // reaching Search.  A TV navigation bar is an ordered remote path, so spell it out.
        stops = ArrayList<View>().apply {
            add(pillProfile)
            add(homeTab)
            add(moviesTab)
            add(seriesTab)
            add(gamesTab)
            add(live)
            add(searchIcon)
            add(gear)
        }
        wireFocus()
        bar.addView(pill, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.CENTER })
        // DOWN from anywhere on the bar returns to the top of the shelves.
        for (i in 0 until pill.childCount) pill.getChildAt(i).nextFocusDownId = shelfListId
        return bar
    }
    // Classic/Netflix navigation uses the wordmark as its Home stop.  It used to be only
    // decoration, leaving `navAnchor` unset for these packs: UP from Hero Play then had no
    // route at all.  Give it the same padded, explicit remote target as the rest of the bar.
    val homeBrand = FrameLayout(activity).apply {
        id = View.generateViewId()
        contentDescription = "Home"
        isClickable = true
        clipChildren = false
        setPadding(theme.px(10f), theme.px(7f), theme.px(10f), theme.px(7f))
        setOnClickListener { callbacks.goTab("") }
    }
    // The wordmark itself has no opaque surface for Android to paint a keyline onto.  A
    // FrameLayout lets Focus use its foreground, keeping the compact ring above the mark
    // without letting a background child influence the bar's wrap-content measurement.
    // The mark's vector canvas is 351x81 dp — enormous if left at wrap-content, where it
    // towered over the bar. The wordmark is a signature, not the page's hero.
    homeBrand.addView(brand, FrameLayout.LayoutParams(
        theme.px(132f), theme.px(30f)
    ).apply { gravity = Gravity.CENTER })
    Focus.attach(homeBrand, homeBrand, app, theme.radius("button", 6f), scale = false, elevate = false,
        onFocus = { if (it) callbacks.onChromeFocused() })
    val movies = navButton("Movies") { callbacks.goTab("movies") }.apply { id = View.generateViewId() }
    val series = navButton("Series") { callbacks.goTab("series") }.apply { id = View.generateViewId() }
    // Games is a mode, not a catalogue tab, in this bar too: without it the only way to the
    // games screens from the Netflix and Classic packs was the settings page. It is a door to
    // an add-on, so it exists only when the household installed the games add-on.
    // The host owns the add-on facts.
    val games = navButton("Games") {
        callbacks.onGames()
    }.apply { id = View.generateViewId() }
    games.visibility = if (showsGames) View.VISIBLE else View.GONE
    // Live TV belongs in every pack's bar, not just the Apple pill — when the hub carries the
    // live add-on and a playlist. The Netflix and Classic bars were the one place a household
    // with channels could not reach them without the left rail.
    val live = navButton("Live TV") {
        callbacks.onLive()
    }.apply { id = View.generateViewId() }
    live.visibility = if (showsLive) View.VISIBLE else View.GONE
    liveStop = live
    profile.id = View.generateViewId()
    search.id = View.generateViewId()
    settings.id = View.generateViewId()
    val bar = LinearLayout(activity)
    bar.orientation = LinearLayout.HORIZONTAL
    bar.gravity = Gravity.CENTER_VERTICAL
    bar.setPadding(theme.gutter, 0, theme.gutter, 0)
    bar.setBackgroundColor(if (transparent) Color.TRANSPARENT else theme.bg)
    bar.addView(homeBrand, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    bar.addView(View(activity), LinearLayout.LayoutParams(0, 1, 1f))
    bar.addView(movies,
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginStart = theme.px(22f)
        })
    bar.addView(series,
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginStart = theme.px(18f)
        })
    bar.addView(games,
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginStart = theme.px(18f)
        })
    bar.addView(live,
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginStart = theme.px(18f)
        })
    bar.addView(profile, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        marginStart = theme.px(24f)
    })
    bar.addView(search, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        marginStart = theme.px(24f)
    })
    bar.addView(settings, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        marginStart = theme.px(28f)
    })
    // Do not leave classic packs to geometric focus search.  The spacer occupies most of the
    // bar, and on TV that made the Home/Play boundary impossible to traverse reliably.
    anchor = homeBrand
    group = bar
    stops = listOf(homeBrand, movies, series, games, live, profile, search, settings)
    wireFocus()
    for (i in 0 until bar.childCount) bar.getChildAt(i).nextFocusDownId = shelfListId
    return bar
}

    fun wireFocus() {
        val visible = stops.filter { it.visibility == View.VISIBLE }
        if (visible.isEmpty()) return
        for (index in visible.indices) {
            visible[index].nextFocusLeftId = visible[(index - 1).coerceAtLeast(0)].id
            visible[index].nextFocusRightId = visible[(index + 1).coerceAtMost(visible.lastIndex)].id
        }
    }

    fun showLive() {
        liveStop?.visibility = View.VISIBLE
        wireFocus()
    }

private fun navButton(
    label: String, left: Int = 0, preAttach: ((TextView) -> Unit)? = null, action: () -> Unit,
): View {
    val b = Views.text(app, "li", 20f, app.theme.ink, label, bold = true, gravity = Gravity.CENTER)
    b.setPadding(app.theme.px(17f), app.theme.px(9f), app.theme.px(17f), app.theme.px(9f))
    b.isFocusable = true
    b.isClickable = true
    b.setOnClickListener { action() }
    // Whatever identity the tab wears — the pill of the section you stand on — goes on before
    // the focus paint below, so walking focus over the tab and away never erases it.
    preAttach?.invoke(b)
    val plain = b.background
    val active = preAttach != null
    // A soft halo band around a 27 px line of text is invisible on a TV panel: the viewer could
    // not tell which tab in the bar held the ring. Chrome items get an explicit outline instead,
    // and the white-faced active tab turns silver while focused so its ring still reads.
    fun paintChrome(focused: Boolean) {
        b.background = when {
            !focused -> plain
            active -> Views.rounded(
                Color.argb(255, 198, 200, 206), app.theme.px(999f),
                app.theme.px(3f), Color.argb(235, 255, 255, 255))
            else -> Views.rounded(
                Color.argb(36, 255, 255, 255), app.theme.px(999f),
                app.theme.px(2f), Color.argb(235, 255, 255, 255))
        }
    }
    paintChrome(false)
    Focus.attach(b, null, app, app.theme.radius("button", 6f), scale = false, elevate = false,
        onFocus = { f -> if (f) callbacks.onChromeFocused(); paintChrome(f) })
    val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    lp.marginStart = left
    b.layoutParams = lp
    return b
}

/**
 * A section of the pill.  The text lives in a child and the selected face on its container;
 * a sibling paints the focus ring.  Painting focus directly onto a TextView was letting Android
 * replace the pill's drawable and intermittently hide the label itself.
 */
private fun navTab(label: String, active: Boolean = false, action: () -> Unit): View {
    val theme = app.theme
    val chip = FrameLayout(activity).apply {
        isFocusable = true
        isClickable = true
        contentDescription = label
        clipChildren = false
        setOnClickListener { action() }
    }
    chip.background = if (active) Views.rounded(theme.brand, theme.px(999f)) else null
    val words = Views.text(app, "nav", 20f, if (active) theme.onBrand else theme.ink, label,
        bold = true, gravity = Gravity.CENTER).apply {
        // The content view never takes focus; focus belongs to the whole padded target.
        isFocusable = false
        isClickable = false
        setPadding(theme.px(20f), theme.px(12f), theme.px(20f), theme.px(12f))
    }
    // A foreground drawable on this Android TV renderer can suppress child text after focus
    // leaves it.  Keep the ring in a separately-sized sibling instead.
    val focusPlate = View(activity)
    chip.addView(focusPlate, FrameLayout.LayoutParams(0, 0))
    chip.addView(words, FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ))
    chip.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
        val lp = focusPlate.layoutParams
        if (lp.width != chip.width || lp.height != chip.height) {
            lp.width = chip.width
            lp.height = chip.height
            focusPlate.layoutParams = lp
        }
    }
    Focus.attach(chip, focusPlate, app, theme.px(999f), scale = false, elevate = false,
        onFocus = { focused ->
            // Home's white selected state is semantic, not a focus effect. Give a focused
            // selected tab a dark keyline so the remote target stays unambiguous.
            if (focused && active) {
                focusPlate.background = Views.ring(Color.argb(225, 20, 21, 26), theme.px(999f), theme.px(3f))
            }
            if (focused) callbacks.onChromeFocused()
        })
    chip.layoutParams = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { marginStart = theme.px(4f) }
    return chip
}

/**
 * The pill's icon actions.  The glyph is fixed inside a full remote target instead of using
 * ImageView padding as both visual and focus geometry; that makes Search and Settings equal in
 * size and gives their focus plate room to read.
 */
private fun navIcon(res: Int, desc: String, action: () -> Unit): View {
    val theme = app.theme
    val hit = FrameLayout(activity).apply {
        isFocusable = true
        isClickable = true
        contentDescription = desc
        clipChildren = false
        setOnClickListener { action() }
    }
    val glyph = ImageView(activity).apply {
        setImageResource(res)
        setColorFilter(theme.ink)
        scaleType = ImageView.ScaleType.FIT_CENTER
    }
    val target = theme.px(56f)
    val glyphSize = theme.px(26f)
    val focusPlate = View(activity)
    hit.addView(focusPlate, FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
    ))
    hit.addView(glyph, FrameLayout.LayoutParams(glyphSize, glyphSize).apply {
        gravity = Gravity.CENTER
    })
    Focus.attach(hit, focusPlate, app, theme.px(999f), scale = false, elevate = false,
        onFocus = { focused ->
            if (focused) {
                // Keep the glyph centred inside a real 56px hit target and draw its focus on
                // the separate plate, never over the icon itself.
                focusPlate.background = Views.ring(theme.focusRingColor, theme.px(999f), theme.px(2f))
            }
            if (focused) callbacks.onChromeFocused()
            hit.background = if (focused) Views.rounded(
                Color.argb(42, 255, 255, 255), theme.px(999f)
            ) else null
        })
    hit.layoutParams = LinearLayout.LayoutParams(target, target).apply {
        marginStart = theme.px(2f); marginEnd = theme.px(2f)
    }
    return hit
}

/** The household at the head of the pill: the profile's face when the hub has one, its initial otherwise. */

}
