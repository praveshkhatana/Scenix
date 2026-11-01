package com.scenix.tv

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import com.scenix.tv.core.AppContext

/**
 * The game player's preloader: a plain themed progress bar, nothing branded.
 *
 * A determinate source (a page's own load progress) paints the fill honestly; while a first play
 * downloads its core there is no progress to read, so the bar sweeps a partial fill across itself.
 * On the first painted frame of the game the fill completes and the bar fades out.
 */
class GameLoader(private val app: AppContext) {

    private val main = Handler(Looper.getMainLooper())
    private val fill: View
    private val box: FrameLayout
    private var p = 0f
    private var loop: Runnable? = null
    private var finished = false

    init {
        val theme = app.theme
        val w = theme.px(340f)
        val h = theme.px(7f)
        box = FrameLayout(app.app).apply { visibility = View.GONE }
        box.addView(View(app.app).apply {
            background = GradientDrawable().apply {
                setColor(Color.argb(90, 255, 255, 255))
                cornerRadius = h / 2f
            }
        }, FrameLayout.LayoutParams(w, h).apply {
            gravity = Gravity.CENTER
        })
        fill = View(app.app).apply {
            background = GradientDrawable().apply {
                setColor(theme.brand)
                cornerRadius = h / 2f
            }
        }
        box.addView(fill, FrameLayout.LayoutParams(w, h).apply {
            gravity = Gravity.CENTER
        })
    }

    /** Drop the loader into a full-screen FrameLayout, above the web view. */
    fun attach(root: FrameLayout) {
        root.addView(box, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ))
    }

    fun show() {
        finished = false
        setProgress(0f)
        box.alpha = 0f
        box.visibility = View.VISIBLE
        box.animate().alpha(1f).setDuration(180).start()
    }

    /** 0..1 from a real page-load signal; also stops the loop — determinate beats decorative. */
    fun setProgress(f: Float) {
        if (finished) return
        stopLoop()
        p = f.coerceIn(0f, 1f)
        apply()
    }

    /** No real progress to read (core still downloading): a partial fill sweeps across. */
    fun loop() {
        if (finished || loop != null) return
        val started = System.currentTimeMillis()
        val r = object : Runnable {
            override fun run() {
                if (finished) return
                val t = ((System.currentTimeMillis() - started) % 2600) / 2600f
                p = if (t < 0.5f) t * 1.4f else 0.7f
                apply()
                main.postDelayed(this, 60)
            }
        }
        loop = r
        main.post(r)
    }

    /** The game painted: complete the fill, then fade the bar out. */
    fun finish() {
        if (finished) return
        finished = true
        stopLoop()
        p = 1f
        apply()
        box.animate().alpha(0f).setStartDelay(240).setDuration(300)
            .withEndAction { box.visibility = View.GONE }.start()
    }

    private fun stopLoop() {
        loop?.let { main.removeCallbacks(it) }
        loop = null
    }

    private fun apply() {
        val w = fill.width
        if (w <= 0) { fill.post { if (!finished && fill.width > 0) apply() }; return }
        fill.scaleX = p
        fill.pivotX = 0f
    }
}
