package com.scenix.tv

import android.animation.ObjectAnimator
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.scenix.tv.core.AppContext
import com.scenix.tv.core.Motion
import com.scenix.tv.core.Screens
import com.scenix.tv.core.Focus
import com.scenix.tv.core.Views

/**
 * A remote-first PIN keypad. The code is checked by the hub; this screen only collects it.
 *
 * Screen pass 2026-11-03 (splash → profile → PIN → home): the gate stands on the pack's wall with
 * its keypad and dots on one frost plate — the same liquid-glass material as the Home chrome,
 * sampled from what actually sits behind it (LiquidFrost) over a translucent fallback. Input has
 * motion: the dots pop on every digit, a wrong PIN shakes the whole plate, keys dip under the
 * press, and the plate rises into the gate rather than snapping in.
 */
class PinActivity : Activity() {

    private lateinit var app: AppContext
    private lateinit var profileId: String
    private lateinit var dots: TextView
    private lateinit var hint: TextView
    private lateinit var keys: LinearLayout
    private lateinit var panel: View
    private var frost: LiquidFrost? = null
    private var pin = StringBuilder()
    private var busy = false
    private val api by lazy { BrowseApi(applicationContext, (application as ScenixApp).prefs) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        app = (application as ScenixApp).ctx
        profileId = intent?.getStringExtra(EXTRA_PROFILE).orEmpty()
        val name = intent?.getStringExtra(EXTRA_NAME).orEmpty()
        if (profileId.isBlank()) { finish(); return }

        val theme = app.theme
        val frame = FrameLayout(this).apply {
            setBackgroundColor(theme.bg)
            Screens.wall(app)?.let { background = it }
        }
        val plateR = theme.radius("hero", 26f)
        val glass = theme.homeLayout.hero == com.scenix.tv.core.HomeLayoutSpec.Hero.GLASS_CARDS
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            // The gate wears the pack's deep glass — the nav pill's own ink at plate weight — so
            // its white type carries full contrast and the rim reads as one edge, not a smudge
            // (the 59% wash over the frost sample had muddy two-tone edges on the light wall).
            // Fallback plate first; the sampled frost layers over it once the capture lands.
            background = Views.rounded(
                theme.color(if (glass) "gatePlate" else "glassSurface",
                    if (glass) "rgba(8,16,28,0.88)" else "rgba(8,10,14,0.59)"),
                plateR, theme.px(1.5f), theme.color("glassRim", "rgba(255,255,255,0.38)"))
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, o: android.graphics.Outline) {
                    o.setRoundRect(0, 0, v.width, v.height, plateR.toFloat())
                }
            }
            elevation = theme.space("plateElevation", 16f) / 2f
            setPadding(theme.px(56f), theme.px(40f), theme.px(56f), theme.px(36f))
        }
        panel = root

        root.addView(Views.text(app, "h1", 44f,
            if (theme.homeLayout.hero == com.scenix.tv.core.HomeLayoutSpec.Hero.GLASS_CARDS)
                theme.color("inkOnWall", "#FFFFFF") else theme.ink,
            "Enter your PIN", bold = true, gravity = Gravity.CENTER))
        if (name.isNotBlank()) {
            root.addView(Views.text(app, "sub", 24f, theme.ink2, name, gravity = Gravity.CENTER),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = theme.px(8f)
                })
        }
        dots = Views.text(app, "h1", 36f,
            if (theme.homeLayout.hero == com.scenix.tv.core.HomeLayoutSpec.Hero.GLASS_CARDS)
                theme.color("inkOnWall", "#FFFFFF") else theme.ink, "○ ○ ○ ○", bold = true, gravity = Gravity.CENTER)
        root.addView(dots, LinearLayout.LayoutParams(theme.px(560f), theme.px(90f)).apply {
            topMargin = theme.px(20f)
        })
        hint = Views.text(app, "meta", 20f, theme.ink3, "Use the number keys or the pad", gravity = Gravity.CENTER)
        root.addView(hint, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, theme.px(42f)))

        keys = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        // The keys ride a dark translucent plate; on the glass pack their ink must be light —
        // the pack's regular ink is near-black and vanished into the key fill.
        val keyInk = if (theme.homeLayout.hero == com.scenix.tv.core.HomeLayoutSpec.Hero.GLASS_CARDS)
            theme.color("inkOnWall", "#FFFFFF") else theme.ink
        val rows = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("CLEAR", "0", "OK"))
        for (labels in rows) {
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
            for (label in labels) {
                val b = Views.text(app, "li", if (label.length > 1) 18f else 28f,
                    if (glass && label == "CLEAR") theme.color("pillInk", "#B7C4D2") else keyInk,
                    label, bold = true, gravity = Gravity.CENTER)
                b.setPadding(app.theme.px(8f), 0, app.theme.px(8f), 0)
                // The keypad keys are the pack's keys: same rounded plate language as Settings' PIN pad.
                val keyRadius = app.theme.radius("key", 12f)
                if (glass) {
                    // Glass keycaps: a light translucent fill with a hairline rim stands clearly on
                    // the deep plate — the legacy opaque grey squares read as dead plastic. OK is
                    // lit like the pack's active chip so the pad has one obvious destination.
                    b.background = Views.rounded(
                        theme.color(if (label == "OK") "chipFillActive" else "keyGlass",
                            "rgba(255,255,255,0.10)"),
                        keyRadius, theme.px(1f), theme.color("glassRim", "rgba(255,255,255,0.38)"))
                } else {
                    b.background = Views.rounded(app.theme.colorOr("keyBg", app.theme.surface2), keyRadius)
                }
                b.isFocusable = true
                b.isClickable = true
                b.setOnClickListener {
                    if (app.theme.motionEnabled) {
                        // Keys dip under the press — the pad answers before the hub does.
                        b.animate().scaleX(0.93f).scaleY(0.93f).setDuration(70).withEndAction {
                            b.animate().scaleX(1f).scaleY(1f).setDuration(110).start()
                        }.start()
                    }
                    press(label)
                }
                Focus.attach(b, b, app, keyRadius, scale = false, elevate = false,
                    onFocus = if (glass) { focused ->
                        // The focused cap lights up with a bright rim so the D-pad target is
                        // unmistakable without hiding the digit under a heavy ring; leaving focus
                        // puts the resting cap back (this callback is the last writer of the
                        // background, so it must paint both states).
                        b.background = Views.rounded(
                            theme.color(if (focused) "chipFillActive"
                                else if (label == "OK") "chipFillActive" else "keyGlass",
                                "rgba(255,255,255,0.10)"),
                            keyRadius,
                            if (focused) theme.px(1.5f) else theme.px(1f),
                            if (focused) theme.color("glassLine", "rgba(255,255,255,0.90)")
                            else theme.color("glassRim", "rgba(255,255,255,0.38)"))
                    } else null)
                line.addView(b, LinearLayout.LayoutParams(app.theme.px(150f), app.theme.px(74f)).apply {
                    marginStart = app.theme.px(8f); marginEnd = app.theme.px(8f)
                })
            }
            keys.addView(line, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, app.theme.px(80f)))
        }
        root.addView(keys, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = theme.px(8f)
        })
        frame.addView(root, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER
        ))
        setContentView(frame)
        Motion.enter(theme, frame, listOf(root))
        val firstRow = keys.getChildAt(0) as? ViewGroup
        (firstRow?.getChildAt(0) ?: keys)?.requestFocus()
        // Real frost on the plate: sample the wall behind it — but only once the plate has finished
        // rising. Capturing mid-entry sampled the animating plate itself and left a lighter inner
        // rectangle with its own edges inside the plate (the muddy double-frame the owner saw).
        root.postDelayed({
            if (!isFinishing) frost = LiquidFrost.install(root, app, plateR, root.background)
        }, if (theme.motionEnabled)
            (theme.token("anim", "fadeUpMs", 600.0) + 250).toLong() else 0L)
    }

    private fun press(label: String) {
        if (busy) return
        when (label) {
            "CLEAR" -> { pin.setLength(0); show() }
            "OK" -> submit()
            else -> if (label.length == 1) {
                if (pin.length < 4) pin.append(label)
                show()
                popDots()
                if (pin.length == 4) submit()
            }
        }
    }

    /** The dots pop with every digit: the gate acknowledges the remote before the hub ever does. */
    private fun popDots() {
        if (!app.theme.motionEnabled) return
        dots.animate().cancel()
        dots.scaleX = 1f; dots.scaleY = 1f
        dots.animate().scaleX(1.18f).scaleY(1.18f).setDuration(90).withEndAction {
            dots.animate().scaleX(1f).scaleY(1f).setDuration(140).start()
        }.start()
    }

    /** A wrong PIN shakes the whole plate side to side — unmistakable without a sound. */
    private fun shakePanel() {
        if (!app.theme.motionEnabled) return
        val px = app.theme.px(16f).toFloat()
        ObjectAnimator.ofFloat(panel, View.TRANSLATION_X, 0f, -px, px * 0.75f, -px * 0.5f, px * 0.25f, 0f)
            .setDuration(420L).start()
    }

    private fun show() {
        dots.text = (0 until 4).joinToString(" ") { if (it < pin.length) "•" else "○" }
        hint.text = "Use the number keys or the pad"
        hint.setTextColor(app.theme.ink3)
    }

    private fun submit() {
        if (pin.length != 4 || busy) return
        busy = true
        hint.text = "Checking…"
        hint.setTextColor(app.theme.ink2)
        api.unlock(profileId, pin.toString()) { ok, error ->
            if (isFinishing || isDestroyed) return@unlock
            busy = false
            if (ok) {
                setResult(RESULT_OK, Intent().putExtra(EXTRA_PROFILE, profileId))
                finish()
            } else {
                pin.setLength(0)
                show()
                shakePanel()
                hint.text = if (error?.contains("403") == true) "That PIN is not right" else (error ?: "That PIN is not right")
                hint.setTextColor(app.theme.color("warn", "#F2C33A"))
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (busy) return true
        when (keyCode) {
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> {
                press((keyCode - KeyEvent.KEYCODE_0).toString())
                return true
            }
            KeyEvent.KEYCODE_DEL -> { if (pin.isNotEmpty()) { pin.deleteCharAt(pin.length - 1); show() }; return true }
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> { submit(); return true }
            KeyEvent.KEYCODE_BACK -> { finish(); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    companion object {
        const val EXTRA_PROFILE = "profile"
        const val EXTRA_NAME = "name"
    }
}
