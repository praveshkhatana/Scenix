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
import android.widget.ScrollView
import android.widget.TextView
import com.scenix.tv.core.AppContext
import com.scenix.tv.core.Motion
import com.scenix.tv.core.Screens
import com.scenix.tv.core.Focus
import com.scenix.tv.core.Views
import org.json.JSONArray
import org.json.JSONObject

/**
 * Debrid on the television: the provider, the on/off switch, and a write-only token. The token is
 * never read back from the hub — the screen only knows "set" or "not set", exactly like the web page.
 */
class DebridActivity : Activity() {

    private lateinit var app: AppContext
    private lateinit var rows: LinearLayout
    private lateinit var status: TextView
    private var settings: JSONObject? = null
    private var info: JSONObject? = null
    private var picker: View? = null
    // The row that opened the last picker; it takes focus back when the picker
    // closes, so the remote never lands on the page's back control by accident.
    private var pickerReturn: View? = null
    private val api by lazy { BrowseApi(applicationContext, (application as ScenixApp).prefs) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        app = (application as ScenixApp).ctx
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val wall = Screens.wall(app)
            if (wall != null) background = wall else setBackgroundColor(app.theme.bg)
        }
        val (bar, barStatus) = Screens.topBar(this, app, "Debrid") { finish() }
        status = barStatus
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, app.theme.px(88f)))
        rows = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(app.theme.gutter, app.theme.px(18f), app.theme.gutter, app.theme.px(40f))
        }
        // The provider list can outgrow the screen; unscrolled, trailing rows are clipped and the
        // D-pad walks into a half-visible row. Same ScrollView wrapper Settings uses.
        val scroll = ScrollView(this).apply {
            isFocusable = false
            isFocusableInTouchMode = false
        }
        scroll.addView(rows)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        Motion.enter(app.theme, root)
        load()
    }

    private fun load() {
        status.text = "Reading debrid…"
        api.settings { root, error ->
            if (isFinishing || isDestroyed) return@settings
            val s = root?.optJSONObject("settings")
            if (s == null) {
                status.text = error ?: "The hub did not send its settings"
                return@settings
            }
            settings = s
            info = root.optJSONObject("debrid")
            status.text = ""
            paint()
        }
    }

    private fun paint() {
        rows.removeAllViews()
        val s = settings ?: return
        val d = s.optJSONObject("debrid") ?: JSONObject()
        val enabled = d.optBoolean("enabled", false)
        val provider = d.optString("provider").ifBlank { "real-debrid" }
        val tokenSet = info?.optBoolean("tokenSet", false) == true ||
            d.optBoolean("tokenSet", false) || d.optString("token").isNotBlank()
        addRow("Debrid", if (enabled) "On · $provider" else "Off", "Use a debrid account for cached torrents") {
            val next = JSONObject(settings.toString())
            val db = next.optJSONObject("debrid") ?: JSONObject().also { next.put("debrid", it) }
            db.put("enabled", !enabled)
            write(next, if (!enabled) "Debrid on" else "Debrid off")
        }
        addRow("Provider", provider, "The account this hub can ask") {
            val providers = info?.optJSONArray("providers") ?: JSONArray()
            val opts = ArrayList<Pair<String, String>>()
            for (i in 0 until providers.length()) {
                val o = providers.optJSONObject(i) ?: continue
                opts.add(o.optString("id") to o.optString("label", o.optString("id")))
            }
            if (opts.isEmpty()) opts.add("real-debrid" to "Real-Debrid")
            showPicker("Debrid provider", opts) { chosen ->
                val next = JSONObject(settings.toString())
                val db = next.optJSONObject("debrid") ?: JSONObject().also { next.put("debrid", it) }
                db.put("provider", chosen)
                write(next, "Provider saved")
            }
        }
        addRow("Token", if (tokenSet) "Set" else "Not set", "Write-only: the hub never sends it back") {
            startActivityForResult(
                Intent(this, TextEntryActivity::class.java)
                    .putExtra(TextEntryActivity.EXTRA_TITLE, "Debrid token")
                    .putExtra(TextEntryActivity.EXTRA_HINT, "Paste the private API token"),
                REQ_TOKEN,
            )
        }
        val note = info?.optString("note").orEmpty()
        if (note.isNotBlank()) {
            rows.addView(Views.text(app, "meta", 17f, app.theme.ink3, note, maxLines = 3),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = app.theme.px(12f)
                })
        }
    }

    private fun addRow(label: String, value: String, note: String, action: () -> Unit) {
        Screens.addRow(this, app, rows, label, value, note, chevron = true, onClick = action)
    }

    private fun showPicker(title: String, options: List<Pair<String, String>>, onPick: (String) -> Unit) {
        closePicker()
        pickerReturn = currentFocus ?: pickerReturn
        val layer = FrameLayout(this).apply { setBackgroundColor(Screens.dimColor(app)) }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(app.theme.px(26f), app.theme.px(20f), app.theme.px(26f), app.theme.px(20f))
            background = Screens.modalFill(app)
        }
        panel.addView(Views.text(app, "li", 25f, app.theme.ink, title, bold = true, maxLines = 2))
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        var first: View? = null
        for ((value, label) in options) {
            val row = Screens.pickerRow(this, app, label, selected = false) {
                closePicker()
                onPick(value)
            }
            list.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, app.theme.px(58f)))
            if (first == null) first = row
        }
        panel.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = app.theme.px(8f)
        })
        layer.addView(panel, FrameLayout.LayoutParams(app.theme.px(620f), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER
        })
        addContentView(layer, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        picker = layer
        first?.post { first?.requestFocus() }
    }

    private fun closePicker() {
        picker?.let { (it.parent as? ViewGroup)?.removeView(it) }
        picker = null
        pickerReturn?.requestFocus()
        pickerReturn = null
    }

    private fun write(next: JSONObject, ok: String) {
        status.text = "Saving…"
        api.saveSettings(JSONObject().put("settings", next)) { _, error ->
            if (isFinishing || isDestroyed) return@saveSettings
            if (error != null) {
                status.text = error
                return@saveSettings
            }
            status.text = ok
            load()
            status.postDelayed({ if (!isFinishing) status.text = "" }, 2500)
        }
    }

    @Deprecated("Activity result API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_TOKEN || resultCode != RESULT_OK) return
        val token = data?.getStringExtra(TextEntryActivity.EXTRA_RESULT).orEmpty().trim()
        if (token.isBlank()) return
        val s = settings ?: return
        val next = JSONObject(s.toString())
        val db = next.optJSONObject("debrid") ?: JSONObject().also { next.put("debrid", it) }
        db.put("token", token)
        // Keep the local copy free of the secret after handing it to the hub.
        write(next, "Token saved")
        settings?.optJSONObject("debrid")?.remove("token")
    }

    override fun onBackPressed() {
        if (picker != null) { closePicker(); return }
        super.onBackPressed()
    }

    companion object {
        private const val REQ_TOKEN = 51
    }
}
