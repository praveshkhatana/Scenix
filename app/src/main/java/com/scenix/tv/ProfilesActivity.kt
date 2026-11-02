package com.scenix.tv

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.scenix.tv.core.AppContext
import com.scenix.tv.core.Motion
import com.scenix.tv.core.Focus
import com.scenix.tv.core.Screens
import com.scenix.tv.core.Views

/** The native front door: pick a person before Browse draws a shelf. PINs are checked by the hub. */
class ProfilesActivity : Activity() {

    private lateinit var app: AppContext
    private lateinit var profileId: String
    private lateinit var row: LinearLayout
    private lateinit var status: TextView
    /** Kept out of the initial focus graph until the household faces have arrived. */
    private var manageProfilesButton: View? = null
    private val api by lazy { BrowseApi(applicationContext, (application as ScenixApp).prefs) }
    private var profiles: List<ProfileInfo> = emptyList()
    // The gate has to show the whole household at the household's zoom. Four faces plus the empty
    // one at 260dp each is wider than a 1080p screen at 1.3x; the last person was sliced off by the
    // right edge. The size is chosen from the count so every face is visible until the count is
    // genuinely large, where the strip scrolls on purpose.
    private var facePx = 0
    /**
     * The framework eagerly assigns focus to the first child of a freshly populated TV row.  That
     * is an implementation detail, not a viewer choice: if that first face is PIN protected, OK
     * immediately opens the wrong gate.  Only an actual post-load remote move gets to override
     * the safe first-unlocked landing target.
     */
    private var profileNavigationStarted = false
    /** A `tv://detail`/`tv://play` route that waits for the person to be chosen. */
    private var pendingRoute: String? = null

    /**
     * The glass pack's wall is a LIGHT surface (measured ~(157,172,179)): every regular ink in the
     * pack is near-white, tuned for dark plates, and vanished into it here — the owner's "text are
     * not readable" (2026-11-02). Text that stands bare on the wall wears the wall's own dark ink;
     * buttons still ride the pack's dark pill with white type.
     */
    private val glass get() = app.theme.homeLayout.hero == com.scenix.tv.core.HomeLayoutSpec.Hero.GLASS_CARDS
    private fun wallInk() = if (glass) app.theme.color("wallInk", "#101821") else app.theme.ink
    private fun wallInk2() = if (glass) app.theme.color("wallInk2", "#41505C") else app.theme.ink2
    private fun wallFaint() = if (glass) app.theme.color("wallInk2", "#41505C") else app.theme.ink3
    private fun wallRing() = if (glass) app.theme.color("wallLine", "rgba(16,24,32,0.50)") else app.theme.line

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        app = (application as ScenixApp).ctx
        profileId = intent?.getStringExtra(BrowseActivity.EXTRA_PROFILE).orEmpty()
        pendingRoute = intent?.getStringExtra(EXTRA_ROUTE)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(app.theme.bg)
            // The front door belongs to the pack too: the glass household stands on its wall,
            // not on flat black (pack-means-everything ruling; screen pass splash → profile).
            Screens.wall(app)?.let { background = it }
            setPadding(app.theme.px(64f), app.theme.px(50f), app.theme.px(64f), app.theme.px(40f))
        }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        // The same wordmark file the Home bar uses, so the front door and the shelves wear one brand.
        head.addView(ImageView(this).apply {
            setImageResource(R.drawable.scenix_wordmark)
            // Same rule as the splash: on the Glass pack the mark floats white on the wall;
            // the brand colour is the wordmark's voice only on the packs that use it.
            setColorFilter(if (app.theme.homeLayout.hero ==
                com.scenix.tv.core.HomeLayoutSpec.Hero.GLASS_CARDS
            ) Color.WHITE else app.theme.brand)
            scaleType = ImageView.ScaleType.FIT_START
            contentDescription = "Scenix"
        }, LinearLayout.LayoutParams(app.theme.px(91f), app.theme.px(32f)))
        head.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        // Managing the household belongs on the household's own door, not two screens away in
        // Settings. The button opens the same editor, PIN gate included.
        val manage = Views.text(app, "li", 20f,
            if (glass) app.theme.color("inkOnWall", "#FFFFFF") else app.theme.ink2,
            "Edit profiles", bold = true, gravity = Gravity.CENTER)
        manage.setPadding(app.theme.px(17f), app.theme.px(9f), app.theme.px(17f), app.theme.px(9f))
        if (glass) manage.background =
            Views.rounded(app.theme.color("pill", "rgba(8,16,28,0.86)"), app.theme.px(999f))
        manage.isFocusable = true
        manage.isClickable = true
        manage.setOnClickListener {
            startActivity(Intent(this, ManageProfilesActivity::class.java))
        }
        Focus.attach(manage, manage, app, app.theme.radius("button", 6f), scale = false, elevate = false)
        // Before the hub answers, the only visible remote stop used to be this administration
        // action. A fast OK at app launch therefore opened profile editing instead of choosing a
        // person. It joins the focus graph only after the household row owns the landing focus.
        manage.isFocusable = false
        manage.isFocusableInTouchMode = false
        manageProfilesButton = manage
        head.addView(manage)
        root.addView(head, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(Views.text(app, "h1", 52f, wallInk(), "Who's watching?", bold = true),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = app.theme.px(38f)
            })

        row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val scroller = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            isFillViewport = true
            // The strip is only a viewport for profile tiles. If it can take focus itself Android
            // may park the initial D-pad target on an invisible scroll container instead of a
            // person, leaving the gate with no visible focus treatment.
            isFocusable = false
            isFocusableInTouchMode = false
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        }
        scroller.addView(row)
        root.addView(scroller, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply {
            topMargin = app.theme.px(34f)
        })
        status = Views.text(app, "sub", 22f, wallInk2(), "Reading the household…", gravity = Gravity.CENTER)
        root.addView(status, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, app.theme.px(64f)))
        setContentView(root)
        Motion.enter(app.theme, root)
        load()
    }

    private fun load() {
        api.profiles { list, error ->
            if (isFinishing || isDestroyed) return@profiles
            if (list == null) {
                status.text = error ?: "The hub is not answering"
                return@profiles
            }
            profiles = list
            status.text = ""
            row.removeAllViews()
            facePx = fitFaceSize(profiles.size + 1)
            for (p in profiles) row.addView(profileTile(p))
            row.addView(addTile())
            // The gate wakes up, it doesn't snap into place: each face rises a hand-width behind the
            // last. A dozen views, transform and alpha only — cheap even on the 2 GB panel. Every
            // number comes from the pack's anim tokens; faces step wider apart than shelf sections.
            val theme = app.theme
            val riseMs = theme.token("anim", "fadeUpMs", 600.0).toLong() * 2L / 5L
            val stepMs = theme.token("anim", "fadeUpStagger", 30.0).toLong() * 3L
            val lift = theme.px(theme.token("anim", "fadeUpShift", 20.0).toFloat()).toFloat()
            for (i in 0 until row.childCount) {
                val c = row.getChildAt(i)
                c.alpha = 0f
                c.translationY = lift
                c.animate().alpha(1f).translationY(0f).setStartDelay(i * stepMs).setDuration(riseMs).start()
            }
            // Android often gives the only early control ("Edit profiles") focus while the
            // household is loading. Once the face tiles are present, land on the remembered
            // person (or the first unlocked person) and retry a few frames if the layout is still
            // settling. The original one-shot request was why the gate could open with no visible
            // focus and a first OK accidentally entered profile administration.
            focusInitialProfile()
        }
    }

    private fun focusInitialProfile() {
        val prefs = (application as ScenixApp).prefs
        // A profile can be locked after it was last used. Never restore focus to that stale
        // remembered entry; opening the gate should prefer a person who can actually enter Home.
        val remembered = profiles.indexOfFirst { !it.locked && prefs.profileUnlocked(it.id) }
        val unlocked = profiles.indexOfFirst { !it.locked }
        val index = when {
            remembered >= 0 -> remembered
            unlocked >= 0 -> unlocked
            else -> 0
        }
        var tries = 0
        val settle = object : Runnable {
            override fun run() {
                // A viewer who has made an actual remote move owns focus; the automatic first-child
                // focus that Android assigns during inflation does not.  Treating that framework
                // stop as a choice was why a cold launch could land on a locked first profile even
                // though a usable person was available one tile to the right.
                if (profileNavigationStarted) return
                val target = row.getChildAt(index)
                // A cold launch begins in touch mode on this Android TV image. A D-pad tile that
                // opts out of touch-mode focus cannot accept the programmatic initial request,
                // which is why the first focus only appeared after a remote key was pressed.
                target?.isFocusableInTouchMode = true
                if (target?.requestFocus() == true) {
                    manageProfilesButton?.isFocusable = true
                    manageProfilesButton?.isFocusableInTouchMode = true
                    return
                }
                if (++tries < 20) {
                    row.postDelayed(this, 120L)
                } else {
                    // Keep management reachable even if the device is in an unusual focus mode;
                    // the status remains visible and the household list can be retried by reload.
                    manageProfilesButton?.isFocusable = true
                    manageProfilesButton?.isFocusableInTouchMode = true
                }
            }
        }
        row.postDelayed(settle, 420L)
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (event.action == android.view.KeyEvent.ACTION_DOWN &&
            event.keyCode in setOf(
                android.view.KeyEvent.KEYCODE_DPAD_UP,
                android.view.KeyEvent.KEYCODE_DPAD_DOWN,
                android.view.KeyEvent.KEYCODE_DPAD_LEFT,
                android.view.KeyEvent.KEYCODE_DPAD_RIGHT,
                android.view.KeyEvent.KEYCODE_DPAD_CENTER,
                android.view.KeyEvent.KEYCODE_ENTER,
            )
        ) {
            // The first remote key can arrive while the Hub request is still inflating the row.
            // In that brief window Android has no visible target, but the old implementation still
            // recorded it as viewer navigation. That cancelled [focusInitialProfile] and left the
            // gate without a ring (or, worse, let a second quick OK land on Edit profiles). Let
            // the gate finish its deterministic landing first; the next key acts on that visible
            // person as expected.
            if (!profileNavigationStarted && !profileRowOwnsFocus() && !managementOwnsFocus()) {
                if (profiles.isNotEmpty()) focusInitialProfile()
                return true
            }
            profileNavigationStarted = true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun profileRowOwnsFocus(): Boolean = containsFocusedDescendant(row)

    private fun managementOwnsFocus(): Boolean = manageProfilesButton?.let(::containsFocusedDescendant) == true

    private fun containsFocusedDescendant(ancestor: View): Boolean {
        var focused: View? = currentFocus
        while (focused != null) {
            if (focused === ancestor) return true
            focused = focused.parent as? View
        }
        return false
    }

    /** The largest face that lets [count] tiles (including the empty one) stand in one row. */
    private fun fitFaceSize(count: Int): Int {
        val theme = app.theme
        val max = theme.px(260f)
        val avail = resources.displayMetrics.widthPixels - theme.px(64f) * 2
        val per = avail / count.coerceAtLeast(1)
        return (per - theme.px(44f)).coerceIn(theme.px(120f), max)
    }

    private fun profileTile(p: ProfileInfo): View {
        val theme = app.theme
        val faceSize = if (facePx > 0) facePx else theme.px(260f)
        val tile = LinearLayout(this).apply {
            id = View.generateViewId()
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(theme.px(22f), theme.px(10f), theme.px(22f), theme.px(10f))
            clipChildren = false
            isFocusable = true
            isClickable = true
            contentDescription = buildString {
                append(p.name)
                if (p.locked) append(", locked profile")
            }
        }
        // Apple's household is a circle of faces, not a wall of posters.
        val face = ProfileAvatar.create(this, app, p.name, p.kids, p.avatar, api, faceSize)
        tile.addView(face, LinearLayout.LayoutParams(faceSize, faceSize))
        tile.addView(Views.text(app, "li", 24f, wallInk(), p.name, bold = true, gravity = Gravity.CENTER),
            LinearLayout.LayoutParams(faceSize, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = theme.px(16f)
            })
        if (p.locked) {
            tile.addView(Views.text(app, "meta", 17f, wallFaint(), "PIN", gravity = Gravity.CENTER),
                LinearLayout.LayoutParams(faceSize + theme.px(60f), ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        // The ring of light rides the face itself, glowing outward into the padding around it,
        // and the face lifts gently under the ring — the gate has motion, like the real thing.
        Focus.attach(tile, face, app, faceSize / 2, scale = true, animateScale = true,
            elevate = false, glowInset = theme.focusGlowInset,
            onFocus = { focused -> paintProfileFocus(face, faceSize, focused) })
        // See [focusInitialProfile]: the first visible TV target must accept focus while the
        // system is still transitioning out of touch mode on cold start.
        tile.isFocusableInTouchMode = true
        tile.setOnClickListener { choose(p) }
        return tile
    }

    /** The last face in the circle is the empty one: it opens the household editor. */
    private fun addTile(): View {
        val theme = app.theme
        val faceSize = if (facePx > 0) facePx else theme.px(260f)
        val tile = LinearLayout(this).apply {
            id = View.generateViewId()
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(theme.px(22f), theme.px(10f), theme.px(22f), theme.px(10f))
            clipChildren = false
            isFocusable = true
            isClickable = true
            contentDescription = "Add profile"
        }
        val face = FrameLayout(this).apply {
            background = Views.rounded(
                Color.TRANSPARENT, faceSize / 2, theme.px(2f), wallRing())
        }
        face.addView(Views.text(app, "h1", 70f, wallFaint(), "+", bold = true, gravity = Gravity.CENTER),
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, faceSize))
        tile.addView(face, LinearLayout.LayoutParams(faceSize, faceSize))
        tile.addView(Views.text(app, "li", 24f, wallInk2(), "Add profile", bold = true, gravity = Gravity.CENTER),
            LinearLayout.LayoutParams(faceSize, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = theme.px(16f)
            })
        Focus.attach(tile, face, app, faceSize / 2, scale = true, animateScale = true,
            elevate = false, glowInset = theme.focusGlowInset,
            onFocus = { focused -> paintProfileFocus(face, faceSize, focused) })
        tile.isFocusableInTouchMode = true
        tile.setOnClickListener {
            // The plus tile is a creation action, not another way to edit the selected person.
            // Carry that intent into the household editor so it opens the new-person flow directly.
            startActivity(Intent(this, ManageProfilesActivity::class.java)
                .putExtra(ManageProfilesActivity.EXTRA_START_ADD, true))
        }
        return tile
    }

    /**
     * A profile is mostly artwork, so the generic lift alone is too subtle on Android TV. Keep the
     * tvOS-style lift but add a restrained white circular keyline: one unmistakable current person
     * without turning the gate into a grid of buttons.
     */
    private fun paintProfileFocus(face: View, faceSize: Int, focused: Boolean) {
        face.foreground = if (focused) {
            Views.ring(Color.argb(238, 255, 255, 255), faceSize / 2, app.theme.focusRingPx)
        } else null
    }

    private fun choose(p: ProfileInfo) {
        if (p.locked) {
            startActivityForResult(
                Intent(this, PinActivity::class.java)
                    .putExtra(PinActivity.EXTRA_PROFILE, p.id)
                    .putExtra(PinActivity.EXTRA_NAME, p.name),
                REQ_PIN
            )
            overridePendingTransition(R.anim.scenix_enter, R.anim.scenix_exit)
            return
        }
        open(p)
    }

    private fun open(p: ProfileInfo) {
        (application as ScenixApp).prefs.markProfileUnlocked(p.id)
        val route = pendingRoute
        pendingRoute = null
        if (route != null && route.startsWith("#/detail/")) {
            val identity = Uri.decode(route.removePrefix("#/detail/"))
            startActivity(
                Intent(this, DetailActivity::class.java)
                    .putExtra(DetailActivity.EXTRA_PROFILE, p.id)
                    .putExtra(DetailActivity.EXTRA_IDENTITY, identity)
            )
            finish()
            return
        }
        if (route != null && route.startsWith("#/play/")) {
            val identity = Uri.decode(route.removePrefix("#/play/"))
            startActivity(
                Intent(this, PlayerActivity::class.java)
                    .putExtra(PlayerActivity.EXTRA_PROFILE, p.id)
                    .putExtra(PlayerActivity.EXTRA_IDENTITY, identity)
                    .putExtra(PlayerActivity.EXTRA_TYPE, "movie")
            )
            finish()
            return
        }
        val fromBrowse = intent?.getBooleanExtra(EXTRA_FROM_BROWSE, false) == true
        val intent = Intent(this, BrowseActivity::class.java)
            .putExtra(BrowseActivity.EXTRA_PROFILE, p.id)
        if (fromBrowse) {
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        startActivity(intent)
        // Home rises into the gate — one continuous motion law across the whole start flow.
        overridePendingTransition(R.anim.scenix_enter, R.anim.scenix_exit)
        finish()
    }

    @Deprecated("Activity result API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PIN && resultCode == RESULT_OK) {
            val p = profiles.firstOrNull { it.id == data?.getStringExtra(PinActivity.EXTRA_PROFILE) } ?: return
            open(p)
        }
    }

    companion object {
        const val EXTRA_FROM_BROWSE = "from_browse"
        const val EXTRA_ROUTE = "route"
        private const val REQ_PIN = 10
    }
}
