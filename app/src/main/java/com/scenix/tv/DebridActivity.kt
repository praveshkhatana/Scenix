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
            setBackgroundColor(app.theme.surface)
        }
        val back = Views.text(app, "li", 26f, app.theme.ink, "‹", bold = true, gravity = Gravity.CENTER)
        back.setPadding(app.theme.px(16f), app.theme.px(6f), app.theme.px(16f), app.theme.px(6f))
        back.isFocusable = true
        back.isClickable = true
        back.setOnClickListener { finish() }
        Focus.attach(back, back, app, app.theme.px(10f), scale = false, elevate = false)
        bar.addView(back, LinearLayout.LayoutParams(app.theme.px(56f), app.theme.px(50f)))
        bar.addView(Views.text(app, "li", 26f, app.theme.ink, "Debrid", bold = true).apply {
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
        row.addView(Views.text(app, "meta", 19f, app.theme.ink2, value, maxLines = 1, gravity = Gravity.END))
        Focus.attach(row, row, app, app.theme.px(10f), scale = false, elevate = false)
        rows.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, app.theme.px(78f)).apply {
            bottomMargin = app.theme.px(8f)
        })
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
