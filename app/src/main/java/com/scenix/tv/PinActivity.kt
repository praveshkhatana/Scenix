package com.scenix.tv

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.scenix.tv.core.AppContext
import com.scenix.tv.core.Focus
import com.scenix.tv.core.Views

/** A remote-first PIN keypad. The code is checked by the hub; this screen only collects it. */
class PinActivity : Activity() {

    private lateinit var app: AppContext
    private lateinit var profileId: String
    private lateinit var dots: TextView
    private lateinit var hint: TextView
    private lateinit var keys: LinearLayout
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

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(Color.rgb(20, 20, 20))
            setPadding(app.theme.px(64f), app.theme.px(48f), app.theme.px(64f), app.theme.px(32f))
        }
        root.addView(Views.text(app, "h1", 44f, app.theme.ink, "Enter your PIN", bold = true, gravity = Gravity.CENTER))
        if (name.isNotBlank()) {
            root.addView(Views.text(app, "sub", 24f, app.theme.ink2, name, gravity = Gravity.CENTER),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = app.theme.px(8f)
                })
        }
        dots = Views.text(app, "h1", 36f, app.theme.ink, "○ ○ ○ ○", bold = true, gravity = Gravity.CENTER)
        root.addView(dots, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, app.theme.px(90f)).apply {
            topMargin = app.theme.px(20f)
        })
        hint = Views.text(app, "meta", 20f, app.theme.ink3, "Use the number keys or the pad", gravity = Gravity.CENTER)
        root.addView(hint, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, app.theme.px(42f)))

        keys = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        val rows = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("CLEAR", "0", "OK"))
        for (labels in rows) {
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
            for (label in labels) {
                val b = Views.text(app, "li", if (label.length > 1) 18f else 28f, app.theme.ink, label, bold = true, gravity = Gravity.CENTER)
                b.setPadding(app.theme.px(8f), 0, app.theme.px(8f), 0)
                b.background = Views.rounded(app.theme.surface2, app.theme.px(10f))
                b.isFocusable = true
                b.isClickable = true
                b.setOnClickListener { press(label) }
                Focus.attach(b, b, app, app.theme.px(10f), scale = false, elevate = false)
                line.addView(b, LinearLayout.LayoutParams(app.theme.px(150f), app.theme.px(74f)).apply {
                    marginStart = app.theme.px(8f); marginEnd = app.theme.px(8f)
                })
            }
            keys.addView(line, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, app.theme.px(80f)))
        }
        root.addView(keys, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 0, 1f).apply {
            topMargin = app.theme.px(8f)
        })
        setContentView(root)
        root.post {
            val firstRow = keys.getChildAt(0) as? ViewGroup
            (firstRow?.getChildAt(0) ?: keys)?.requestFocus()
        }
    }

    private fun press(label: String) {
        if (busy) return
        when (label) {
            "CLEAR" -> { pin.setLength(0); show() }
            "OK" -> submit()
            else -> if (label.length == 1) {
                if (pin.length < 4) pin.append(label)
                show()
                if (pin.length == 4) submit()
            }
        }
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
