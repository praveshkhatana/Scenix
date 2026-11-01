package com.scenix.tv

import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import com.scenix.tv.core.AppContext

/**
 * The game player's preloader: the Scenix wordmark itself, filling left-to-right like a bar.
 *
 * A determinate source (a page's own load progress) paints the fill honestly; while a first play
 * downloads its core and ROM there is no progress to read, so the fill loops — the screen says
 * "this is loading" in the same shape either way. On the first painted frame of the game the fill
 * completes and the whole mark fades out.
 */
class GameLoader(private val app: AppContext) {

    private val main = Handler(Looper.getMainLooper())
    private val lit: ImageView
    private val box: FrameLayout
    private var p = 0f
    private var loop: Runnable? = null
    private var finished = false

    init {
        val theme = app.theme
        val w = theme.px(460f)
        val h = theme.px(150f)
        box = FrameLayout(app.app).apply { visibility = View.GONE }
        // The wordmark drawn once dim and once bright, the bright copy clipped to the fill width.
        // Both share the exact vector the rest of the app uses — the logo IS the progress bar.
        val dim = ImageView(app.app).apply {
            setImageResource(R.drawable.scenix_wordmark)
            scaleType = ImageView.ScaleType.FIT_CENTER
            imageAlpha = 70
        }
        lit = ImageView(app.app).apply {
            setImageResource(R.drawable.scenix_wordmark)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        box.addView(dim, FrameLayout.LayoutParams(w, h).apply {
            gravity = Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM
            bottomMargin = theme.px(90f)
        })
        box.addView(lit, FrameLayout.LayoutParams(w, h).apply {
            gravity = Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM
            bottomMargin = theme.px(90f)
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

    /** No real progress to read (core still downloading): the fill sweeps across on its own. */
    fun loop() {
        if (finished || loop != null) return
        val started = System.currentTimeMillis()
        val r = object : Runnable {
            override fun run() {
                if (finished) return
                val t = ((System.currentTimeMillis() - started) % 2600) / 2600f
                p = t
                apply()
                main.postDelayed(this, 60)
            }
        }
        loop = r
        main.post(r)
    }

    /** The game painted: complete the fill, then fade the whole mark out. */
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
        val w = lit.width
        if (w <= 0) { lit.post { applyQuiet() }; return }
        lit.clipBounds = Rect(0, 0, (w * p).toInt(), lit.height)
    }

    private fun applyQuiet() {
        if (finished || lit.width <= 0) return
        lit.clipBounds = Rect(0, 0, (lit.width * p).toInt(), lit.height)
    }
}
