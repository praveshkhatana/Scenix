package com.scenix.tv

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.scenix.tv.core.AppContext
import com.scenix.tv.core.Focus
import com.scenix.tv.core.Views

/**
 * One native text field, for the few settings that are genuinely text: an add-on or source-pack URL,
 * a Debrid token, a playlist address. It is deliberately a separate screen rather than a dialog so
 * the platform IME has the whole panel, the D-pad can walk its keys, and Back always means cancel.
 */
class TextEntryActivity : Activity() {

    private lateinit var app: AppContext
    private lateinit var field: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        app = (application as ScenixApp).ctx
        val title = intent?.getStringExtra(EXTRA_TITLE).orEmpty()
        val value = intent?.getStringExtra(EXTRA_VALUE).orEmpty()
        val hint = intent?.getStringExtra(EXTRA_HINT).orEmpty()
        val plainText = intent?.getBooleanExtra(EXTRA_PLAIN_TEXT, false) == true

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(app.theme.bg)
            setPadding(app.theme.gutter, app.theme.px(42f), app.theme.gutter, app.theme.px(34f))
        }
        root.addView(Views.text(app, "li", 30f, app.theme.ink, title.ifBlank { "Enter a value" }, bold = true))
        if (hint.isNotBlank()) {
            root.addView(Views.text(app, "meta", 18f, app.theme.ink3, hint, maxLines = 2),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = app.theme.px(6f)
                })
        }

        field = EditText(this).apply {
            setText(value)
            setSelection(text.length)
            setHintTextColor(app.theme.ink3)
            setTextColor(app.theme.ink)
            setHint(hint)
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or if (plainText) {
                // A person's name needs spaces; a URL or token wants the URI keyboard. The rest of
                // the settings keep the URI default they were written against.
                InputType.TYPE_TEXT_VARIATION_PERSON_NAME
            } else {
                InputType.TYPE_TEXT_VARIATION_URI
            }
            imeOptions = EditorInfo.IME_ACTION_DONE
            setBackgroundColor(app.theme.surface2)
            setPadding(app.theme.px(18f), app.theme.px(14f), app.theme.px(18f), app.theme.px(14f))
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, app.theme.px(26f).toFloat())
            isFocusable = true
            isFocusableInTouchMode = true
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    save()
                    true
                } else false
            }
        }
        root.addView(field, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, app.theme.px(72f)).apply {
            topMargin = app.theme.px(20f)
        })

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val save = button("Save") { save() }
        val cancel = button("Cancel") { finish() }
        row.addView(save, LinearLayout.LayoutParams(app.theme.px(150f), app.theme.px(56f)))
        row.addView(cancel, LinearLayout.LayoutParams(app.theme.px(150f), app.theme.px(56f)).apply {
            marginStart = app.theme.px(14f)
        })
        root.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = app.theme.px(22f)
        })

        root.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        setContentView(root)
        field.requestFocus()
        field.postDelayed({
            field.requestFocus()
            val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.showSoftInput(field, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }, 120)
    }

    private fun button(label: String, action: () -> Unit): TextView {
        val b = Views.text(app, "li", 22f, app.theme.ink, label, bold = true, gravity = Gravity.CENTER)
        b.background = Views.rounded(app.theme.surface2, app.theme.px(9f))
        b.isFocusable = true
        b.isClickable = true
        b.setOnClickListener { action() }
        Focus.attach(b, b, app, app.theme.px(9f), scale = false, elevate = false)
        return b
    }

    private fun save() {
        val text = field.text?.toString().orEmpty().trim()
        setResult(RESULT_OK, Intent().putExtra(EXTRA_RESULT, text))
        finish()
    }

    companion object {
        const val EXTRA_TITLE = "title"
        const val EXTRA_VALUE = "value"
        const val EXTRA_HINT = "hint"
        const val EXTRA_PLAIN_TEXT = "plain_text"
        const val EXTRA_RESULT = "text"
    }
}
