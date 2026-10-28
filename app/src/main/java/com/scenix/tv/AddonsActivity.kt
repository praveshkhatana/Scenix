package com.scenix.tv

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.scenix.tv.core.AppContext
import com.scenix.tv.core.Focus
import com.scenix.tv.core.Views
import org.json.JSONArray
import org.json.JSONObject

/** The hub's installed sources, switchable and removable, with one native URL field to add another. */
class AddonsActivity : Activity() {

    private lateinit var app: AppContext
    private lateinit var rows: LinearLayout
    private lateinit var status: TextView
    private var installed: JSONArray? = null
    private var picker: View? = null
    private val api by lazy { BrowseApi(applicationContext, (application as ScenixApp).prefs) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        app = (application as ScenixApp).ctx
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(app.theme.bg)
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(app.theme.gutter, 0, app.theme.gutter, 0)
            setBackgroundColor(Color.rgb(20, 20, 20))
        }
        val back = Views.text(app, "li", 26f, app.theme.ink, "‹", bold = true, gravity = Gravity.CENTER)
        back.setPadding(app.theme.px(16f), app.theme.px(6f), app.theme.px(16f), app.theme.px(6f))
        back.isFocusable = true
        back.isClickable = true
        back.setOnClickListener { finish() }
        Focus.attach(back, back, app, app.theme.px(10f), scale = false, elevate = false)
        bar.addView(back, LinearLayout.LayoutParams(app.theme.px(56f), app.theme.px(50f)))
        bar.addView(Views.text(app, "li", 26f, app.theme.ink, "Add-ons", bold = true).apply {
            setPadding(app.theme.px(20f), 0, 0, 0)
        })
        bar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        status = Views.text(app, "meta", 19f, app.theme.ink2, "", maxLines = 1)
        bar.addView(status)
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, app.theme.px(88f)))

        rows = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(app.theme.gutter, app.theme.px(18f), app.theme.gutter, app.theme.px(40f))
        }
        root.addView(rows, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        load()
    }

    private fun load() {
        status.text = "Reading sources…"
        api.addons { list, error ->
            if (isFinishing || isDestroyed) return@addons
            if (list == null) {
                status.text = error ?: "The hub did not send its add-ons"
                return@addons
            }
            installed = list
            status.text = ""
            paint()
        }
    }

    private fun paint() {
        rows.removeAllViews()
        val list = installed ?: return
        for (i in 0 until list.length()) {
            val ad = list.optJSONObject(i) ?: continue
            val name = ad.optString("name").ifBlank { ad.optString("id") }
            val on = ad.optBoolean("enabled", true)
            val native = ad.optBoolean("native", false)
            val detail = if (native) "Built in" else {
                ad.optString("url").replace(Regex("^https?://"), "").take(48)
            }
            val note = if (!ad.isNull("error") && ad.optString("error").isNotBlank()) {
                "Last error: " + ad.optString("error").take(50)
            } else detail
            addRow(name, note, if (on) "On" else "Off") { showActions(ad, name, on, native) }
        }
        addRow("Add another source", "A manifest URL — the hub validates it before keeping it", "") {
            startActivityForResult(
                Intent(this, TextEntryActivity::class.java)
                    .putExtra(TextEntryActivity.EXTRA_TITLE, "Add an add-on")
                    .putExtra(TextEntryActivity.EXTRA_HINT, "https://…/manifest.json"),
                REQ_URL,
            )
        }
    }

    private fun addRow(label: String, note: String, value: String, action: () -> Unit) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(app.theme.px(20f), 0, app.theme.px(20f), 0)
            background = Views.rounded(app.theme.surface, app.theme.px(10f))
            isFocusable = true
            isClickable = true
            setOnClickListener { action() }
        }
        val words = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        words.addView(Views.text(app, "li", 23f, app.theme.ink, label, bold = true, maxLines = 1))
        if (note.isNotBlank()) {
            words.addView(Views.text(app, "meta", 16f, app.theme.ink3, note, maxLines = 1),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        row.addView(words, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (value.isNotBlank()) {
            row.addView(Views.text(app, "meta", 19f, app.theme.ink2, value, maxLines = 1, gravity = Gravity.END))
        }
        Focus.attach(row, row, app, app.theme.px(10f), scale = false, elevate = false)
        rows.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, app.theme.px(78f)).apply {
            bottomMargin = app.theme.px(8f)
        })
    }

    private fun showActions(ad: JSONObject, name: String, on: Boolean, native: Boolean) {
        val options = ArrayList<Pair<String, String>>()
        options.add("toggle" to (if (on) "Turn $name off" else "Turn $name on"))
        if (!native) options.add("remove" to "Remove $name")
        options.add("back" to "Back")
        showPicker(name, options) { action ->
            when (action) {
                "toggle" -> {
                    val id = ad.optString("id")
                    status.text = "Saving…"
                    api.setAddon(id, !on) { _, error ->
                        if (isFinishing || isDestroyed) return@setAddon
                        if (error != null) status.text = error else load()
                    }
                }
                "remove" -> {
                    val id = ad.optString("id")
                    status.text = "Removing…"
                    api.removeAddon(id) { root, error ->
                        if (isFinishing || isDestroyed) return@removeAddon
                        if (error != null) { status.text = error; return@removeAddon }
                        installed = root?.optJSONArray("addons") ?: installed
                        load()
                    }
                }
            }
        }
    }

    private fun showPicker(title: String, options: List<Pair<String, String>>, onPick: (String) -> Unit) {
        closePicker()
        val layer = FrameLayout(this).apply { setBackgroundColor(Color.argb(190, 0, 0, 0)) }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(app.theme.px(26f), app.theme.px(20f), app.theme.px(26f), app.theme.px(20f))
            background = Views.rounded(app.theme.color("panelScrim", "#E6000000"), app.theme.px(12f),
                app.theme.px(1f), app.theme.line)
        }
        panel.addView(Views.text(app, "li", 25f, app.theme.ink, title, bold = true, maxLines = 2))
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        var first: View? = null
        for ((value, label) in options) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(app.theme.px(14f), 0, app.theme.px(14f), 0)
                isFocusable = true
                isClickable = true
                setOnClickListener {
                    closePicker()
                    onPick(value)
                }
            }
            row.addView(Views.text(app, "li", 21f, app.theme.ink, label, maxLines = 1),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            Focus.attach(row, row, app, app.theme.px(8f), scale = false, elevate = false)
            list.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, app.theme.px(58f)))
            if (first == null) first = row
        }
        panel.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = app.theme.px(8f)
        })
        layer.addView(panel, FrameLayout.LayoutParams(app.theme.px(640f), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER
        })
        addContentView(layer, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        picker = layer
        first?.post { first?.requestFocus() }
    }

    private fun closePicker() {
        picker?.let { (it.parent as? ViewGroup)?.removeView(it) }
        picker = null
    }

    @Deprecated("Activity result API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_URL || resultCode != RESULT_OK) return
        val url = data?.getStringExtra(TextEntryActivity.EXTRA_RESULT).orEmpty().trim()
        if (url.isBlank()) return
        status.text = "Adding…"
        api.addAddon(url) { root, error ->
            if (isFinishing || isDestroyed) return@addAddon
            if (error != null) {
                status.text = error
                return@addAddon
            }
            installed = root?.optJSONArray("addons") ?: installed
            load()
        }
    }

    override fun onBackPressed() {
        if (picker != null) { closePicker(); return }
        super.onBackPressed()
    }

    companion object {
        private const val REQ_URL = 41
    }
}
