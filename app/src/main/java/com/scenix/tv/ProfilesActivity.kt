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
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.scenix.tv.core.AppContext
import com.scenix.tv.core.Focus
import com.scenix.tv.core.Views

/** The native front door: pick a person before Browse draws a shelf. PINs are checked by the hub. */
class ProfilesActivity : Activity() {

    private lateinit var app: AppContext
    private lateinit var profileId: String
    private lateinit var row: LinearLayout
    private lateinit var status: TextView
    private val api by lazy { BrowseApi(applicationContext, (application as ScenixApp).prefs) }
    private var profiles: List<ProfileInfo> = emptyList()
    /** A `tv://detail`/`tv://play` route that waits for the person to be chosen. */
    private var pendingRoute: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        app = (application as ScenixApp).ctx
        profileId = intent?.getStringExtra(BrowseActivity.EXTRA_PROFILE).orEmpty()
        pendingRoute = intent?.getStringExtra(EXTRA_ROUTE)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(app.theme.bg)
            setPadding(app.theme.px(64f), app.theme.px(50f), app.theme.px(64f), app.theme.px(40f))
        }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(Views.text(app, "logo", 34f, app.theme.brand, "SCENIX", bold = true))
        head.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        head.addView(Views.text(app, "sub", 22f, app.theme.ink2, "Manage profiles in Settings"))
        root.addView(head, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(Views.text(app, "h1", 52f, app.theme.ink, "Who's watching?", bold = true),
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
        }
        scroller.addView(row)
        root.addView(scroller, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply {
            topMargin = app.theme.px(34f)
        })
        status = Views.text(app, "sub", 22f, app.theme.ink2, "Reading the household…", gravity = Gravity.CENTER)
        root.addView(status, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, app.theme.px(64f)))
        setContentView(root)
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
            for (p in profiles) row.addView(profileTile(p))
            row.postDelayed({
                firstFocus(row)?.requestFocus()
            }, 180)
        }
    }

    private fun profileTile(p: ProfileInfo): View {
        val theme = app.theme
        val faceSize = theme.px(260f)
        val tile = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(theme.px(30f), theme.px(10f), theme.px(30f), theme.px(10f))
            clipChildren = false
            isFocusable = true
            isClickable = true
        }
        // Apple's household is a circle of faces, not a wall of posters.
        val face = FrameLayout(this).apply {
            setBackgroundColor(theme.surface2)
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, v.width, v.height, faceSize / 2f)
                }
            }
        }
        val img = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(theme.surface2)
        }
        face.addView(img, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        if (p.kids) {
            face.addView(View(this).apply {
                background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                    intArrayOf(Color.TRANSPARENT, Color.argb(80, 242, 195, 58)))
            }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        tile.addView(face, LinearLayout.LayoutParams(faceSize, faceSize))
        BrowseImages.load(img, api.absolute(p.avatar), faceSize)
        tile.addView(Views.text(app, "li", 24f, theme.ink, p.name, bold = true, gravity = Gravity.CENTER),
            LinearLayout.LayoutParams(faceSize + theme.px(60f), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = theme.px(16f)
            })
        if (p.locked) {
            tile.addView(Views.text(app, "meta", 17f, theme.ink3, "PIN", gravity = Gravity.CENTER),
                LinearLayout.LayoutParams(faceSize + theme.px(60f), ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        // The ring of light rides the face itself, glowing outward into the padding around it.
        Focus.attach(tile, face, app, faceSize / 2, scale = false, elevate = false,
            glowInset = theme.px(30f))
        tile.setOnClickListener { choose(p) }
        return tile
    }

    private fun choose(p: ProfileInfo) {
        if (p.locked) {
            startActivityForResult(
                Intent(this, PinActivity::class.java)
                    .putExtra(PinActivity.EXTRA_PROFILE, p.id)
                    .putExtra(PinActivity.EXTRA_NAME, p.name),
                REQ_PIN
            )
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

    private fun firstFocus(parent: ViewGroup): View? {
        for (i in 0 until parent.childCount) {
            val c = parent.getChildAt(i)
            if (c.isFocusable && c.visibility == View.VISIBLE) return c
            if (c is ViewGroup) firstFocus(c)?.let { return it }
        }
        return null
    }

    companion object {
        const val EXTRA_FROM_BROWSE = "from_browse"
        const val EXTRA_ROUTE = "route"
        private const val REQ_PIN = 10
    }
}
