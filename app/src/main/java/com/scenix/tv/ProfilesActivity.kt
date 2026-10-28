package com.scenix.tv

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        app = (application as ScenixApp).ctx
        profileId = intent?.getStringExtra(BrowseActivity.EXTRA_PROFILE).orEmpty()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(app.theme.bg)
            setPadding(app.theme.px(64f), app.theme.px(50f), app.theme.px(64f), app.theme.px(40f))
        }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(Views.text(app, "logo", 34f, app.theme.brand, "SCENIX", bold = true))
        head.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        head.addView(Views.text(app, "sub", 22f, app.theme.ink2, "Manage profiles in the web UI"))
        root.addView(head, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(Views.text(app, "h1", 52f, app.theme.ink, "Who's watching?", bold = true),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = app.theme.px(38f)
            })

        row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val scroller = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
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
        val tile = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(app.theme.px(18f), app.theme.px(8f), app.theme.px(18f), app.theme.px(8f))
            isFocusable = true
            isClickable = true
        }
        val face = FrameLayout(this).apply {
            setBackgroundColor(app.theme.surface2)
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, v.width, v.height, app.theme.px(32f).toFloat())
                }
            }
        }
        val img = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(app.theme.surface2)
        }
        face.addView(img, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        if (p.kids) {
            face.addView(View(this).apply {
                background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                    intArrayOf(Color.TRANSPARENT, Color.argb(80, 242, 195, 58)))
            }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        tile.addView(face, LinearLayout.LayoutParams(app.theme.px(240f), app.theme.px(360f)))
        BrowseImages.load(img, api.absolute(p.avatar), app.theme.px(240f))
        tile.addView(Views.text(app, "li", 24f, app.theme.ink, p.name, bold = true, gravity = Gravity.CENTER),
            LinearLayout.LayoutParams(viewWidth(), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = app.theme.px(12f)
            })
        if (p.locked) {
            tile.addView(Views.text(app, "meta", 17f, app.theme.ink3, "PIN", gravity = Gravity.CENTER),
                LinearLayout.LayoutParams(viewWidth(), ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        Focus.attach(tile, tile, app, app.theme.px(32f), elevate = false)
        tile.setOnClickListener { choose(p) }
        return tile
    }

    private fun viewWidth(): Int = app.theme.px(270f)

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
        private const val REQ_PIN = 10
    }
}
