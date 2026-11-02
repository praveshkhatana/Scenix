package com.scenix.tv

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.scenix.tv.core.AppContext
import com.scenix.tv.core.Focus
import com.scenix.tv.core.Views
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

/**
 * The owner's source packs, natively: the picture pack (IGC) and the audio-only pack that the hub
 * mixes into copies missing a language. The web settings page edits the same `settings.igc.sources`
 * / `settings.audio.sources` shape; this screen is the television's way in, with no WebView.
 *
 * Adding a pack is three text fields in sequence (name, URL, languages) rather than one dense form:
 * on a D-pad each field can take the whole IME, and Back cancels a step without losing the rest.
 */
class SourcePacksActivity : Activity() {

    private lateinit var app: AppContext
    private lateinit var rows: LinearLayout
    private lateinit var status: TextView
    private var settings: JSONObject? = null
    private var picker: View? = null
    /** The row that had the remote before a picker opened; focus returns there on close. */
    private var pickerReturn: View? = null
    private val api by lazy { BrowseApi(applicationContext, (application as ScenixApp).prefs) }

    private var pendingKind = ""
    private var pendingName = ""
    private var pendingUrl = ""
    private var step = 0

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
        bar.addView(Views.text(app, "li", 26f, app.theme.ink, "Source packs", bold = true).apply {
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

    override fun onResume() {
        super.onResume()
        if (settings != null) paint()
    }

    private fun load() {
        status.text = "Reading source packs…"
        api.settings { root, error ->
            if (isFinishing || isDestroyed) return@settings
            val s = root?.optJSONObject("settings")
            if (s == null) {
                status.text = error ?: "The hub did not send its settings"
                return@settings
            }
            settings = s
            status.text = ""
            paint()
        }
    }

    private fun paint() {
        // The first focusable view in a settings screen used to be the Back chevron in the top bar:
        // a viewer who opened a section and pressed OK went straight back out (or, while the list
        // was still loading, saw a dead remote). Land on the first real control instead; UP still
        // reaches the bar.
        // A rebuild (after a toggle or a save) used to drop focus to the Back chevron, so the next
        // press went out of the screen. Remember the focused row by position and put the remote
        // back there after the list is rebuilt; a first paint lands on the first real control.
        val keepAt = (0 until rows.childCount).firstOrNull { rows.getChildAt(it).isFocused } ?: -1
        rows.post {
            if (isFinishing || isDestroyed) return@post
            if (keepAt in 0 until rows.childCount) rows.getChildAt(keepAt).requestFocus()
            else Focus.focusFirst(rows)
        }
        rows.removeAllViews()
        val s = settings ?: return
        section("Audio-only packs", "Audio donors the hub mixes into a copy that lacks your language.")
        packs(s, "audio", audio = true)
        addRow("Add an audio source", "A URL, its language, and the hub can mix it in") {
            startAdd("audio")
        }
        section("Picture packs (IGC)", "Owner-defined video sources; they join the normal ranked copies.")
        packs(s, "igc", audio = false)
        addRow("Add a picture source", "A URL template and the languages it carries") {
            startAdd("igc")
        }
    }

    private fun section(title: String, note: String) {
        rows.addView(Views.text(app, "li", 26f, app.theme.ink, title, bold = true),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = app.theme.px(18f)
            })
        rows.addView(Views.text(app, "meta", 17f, app.theme.ink3, note, maxLines = 2),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = app.theme.px(2f)
                bottomMargin = app.theme.px(10f)
            })
    }

    private fun packs(s: JSONObject, kind: String, audio: Boolean) {
        val list = s.optJSONObject(kind)?.optJSONArray("sources")
        if (list == null || list.length() == 0) {
            noteRow(if (audio) "No audio donors yet. A copy without Hindi/English can only be mixed if one is added here."
            else "No picture packs yet.")
            return
        }
        for (i in 0 until list.length()) {
            val o = list.optJSONObject(i) ?: continue
            val name = o.optString("name").ifBlank { o.optString("id") }.ifBlank { "Source" }
            val langs = o.optJSONArray("languages")?.let { arr ->
                (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() }.joinToString(", ")
            }.orEmpty()
            val url = o.optString("url")
            val host = runCatching { URI(url).host.orEmpty() }.getOrDefault("")
            addRow(name, listOf(langs, host).filter { it.isNotBlank() }.joinToString(" · ")) {
                showActions(kind, i, name)
            }
        }
    }

    private fun noteRow(text: String) {
        rows.addView(Views.text(app, "meta", 18f, app.theme.ink3, text, maxLines = 3),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = app.theme.px(10f)
            })
    }

    private fun addRow(label: String, note: String, action: () -> Unit) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(app.theme.px(20f), 0, app.theme.px(20f), 0)
            background = Views.rounded(app.theme.surface, app.theme.px(10f))
            isFocusable = true
            isClickable = true
            setOnClickListener { action() }
        }
        row.addView(Views.text(app, "li", 23f, app.theme.ink, label, bold = true, maxLines = 1),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(Views.text(app, "meta", 17f, app.theme.ink3, note, maxLines = 1, gravity = Gravity.END),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        Focus.attach(row, row, app, app.theme.px(10f), scale = false, elevate = false)
        rows.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, app.theme.px(78f)).apply {
            bottomMargin = app.theme.px(8f)
        })
    }

    private fun showActions(kind: String, index: Int, name: String) {
        showPicker(name, listOf("remove" to "Remove this source", "back" to "Back")) { action ->
            if (action == "remove") removeSource(kind, index)
        }
    }

    private fun showPicker(title: String, options: List<Pair<String, String>>, onPick: (String) -> Unit) {
        if (picker == null) pickerReturn = currentFocus
        closePicker()
        val layer = android.widget.FrameLayout(this).apply { setBackgroundColor(Color.argb(190, 0, 0, 0)) }
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
        layer.addView(panel, android.widget.FrameLayout.LayoutParams(app.theme.px(620f), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER
        })
        addContentView(layer, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        picker = layer
        first?.post { first?.requestFocus() }
    }

    private fun closePicker() {
        picker?.let { (it.parent as? ViewGroup)?.removeView(it) }
        val wasOpen = picker != null
        picker = null
        if (wasOpen) {
            pickerReturn?.takeIf { it.isAttachedToWindow }?.requestFocus()
            pickerReturn = null
        }
    }

    private fun startAdd(kind: String) {
        pendingKind = kind
        pendingName = ""
        pendingUrl = ""
        step = 0
        promptName()
    }

    private fun promptName() {
        startActivityForResult(
            Intent(this, TextEntryActivity::class.java)
                .putExtra(TextEntryActivity.EXTRA_TITLE, "Source name")
                .putExtra(TextEntryActivity.EXTRA_HINT, "A short name, e.g. Hindi audio"),
            REQ_TEXT,
        )
    }

    private fun promptUrl() {
        startActivityForResult(
            Intent(this, TextEntryActivity::class.java)
                .putExtra(TextEntryActivity.EXTRA_TITLE, "Source URL")
                .putExtra(TextEntryActivity.EXTRA_HINT, "https://…  ({title}, {year}, {lang} are substituted)"),
            REQ_TEXT,
        )
    }

    private fun promptLangs() {
        startActivityForResult(
            Intent(this, TextEntryActivity::class.java)
                .putExtra(TextEntryActivity.EXTRA_TITLE, "Languages")
                .putExtra(TextEntryActivity.EXTRA_VALUE, "hin,eng")
                .putExtra(TextEntryActivity.EXTRA_HINT, "ISO codes, comma separated (hin,eng,pan)"),
            REQ_TEXT,
        )
    }

    @Deprecated("Activity result API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_TEXT || resultCode != RESULT_OK) {
            if (requestCode == REQ_TEXT) step = 0
            return
        }
        val text = data?.getStringExtra(TextEntryActivity.EXTRA_RESULT).orEmpty().trim()
        when (step) {
            0 -> {
                if (text.isBlank()) return
                pendingName = text
                step = 1
                promptUrl()
            }
            1 -> {
                if (text.isBlank()) { step = 0; return }
                pendingUrl = text
                step = 2
                promptLangs()
            }
            2 -> {
                val langs = text.split(',', ' ').map { it.trim().lowercase() }.filter { it.isNotBlank() }
                if (langs.isEmpty()) { step = 0; return }
                saveSource(pendingKind, pendingName, pendingUrl, langs.distinct())
                step = 0
            }
        }
    }

    private fun saveSource(kind: String, name: String, url: String, langs: List<String>) {
        val s = JSONObject(settings.toString())
        val pack = s.optJSONObject(kind) ?: JSONObject().also { s.put(kind, it) }
        val sources = pack.optJSONArray("sources") ?: JSONArray().also { pack.put("sources", it) }
        val source = JSONObject()
            .put("id", "tv-" + System.currentTimeMillis())
            .put("name", name)
            .put("url", url)
            .put("type", "movie")
            .put("enabled", true)
            .put("languages", JSONArray(langs))
        if (kind == "igc") source.put("quality", "1080p")
        sources.put(source)
        status.text = "Saving…"
        api.saveSettings(JSONObject().put("settings", s)) { _, error ->
            if (isFinishing || isDestroyed) return@saveSettings
            if (error != null) {
                status.text = error
                return@saveSettings
            }
            status.text = "Added"
            load()
            status.postDelayed({ if (!isFinishing) status.text = "" }, 2500)
        }
    }

    private fun removeSource(kind: String, index: Int) {
        val s = JSONObject(settings.toString())
        val sources = s.optJSONObject(kind)?.optJSONArray("sources") ?: return
        val next = JSONArray()
        for (i in 0 until sources.length()) if (i != index) next.put(sources.optJSONObject(i))
        s.optJSONObject(kind)?.put("sources", next)
        status.text = "Saving…"
        api.saveSettings(JSONObject().put("settings", s)) { _, error ->
            if (isFinishing || isDestroyed) return@saveSettings
            if (error != null) {
                status.text = error
                return@saveSettings
            }
            status.text = "Removed"
            load()
            status.postDelayed({ if (!isFinishing) status.text = "" }, 2500)
        }
    }

    override fun onBackPressed() {
        if (picker != null) {
            closePicker()
            return
        }
        super.onBackPressed()
    }

    companion object {
        private const val REQ_TEXT = 31
    }
}
