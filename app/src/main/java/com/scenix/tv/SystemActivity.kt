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
 * The household's own copy of the hub's system settings: what the box calls itself, which languages it
 * asks its addons for, and how many / how large a copy may be before it is worth ranking. The web's
 * settings screen edits the very same `/app/v1/settings` document; this page is the television's way in,
 * with the D-pad's row + picker voice and a native text field for the one free-form value.
 *
 * Every write sends back the whole settings object it read, with one field changed. The hub merges that
 * and strips `lock`, so the admin PIN and the write-only Debrid token survive untouched.
 */
class SystemActivity : Activity() {

    private lateinit var app: AppContext
    private lateinit var rows: LinearLayout
    private lateinit var status: TextView
    private var settings: JSONObject? = null
    private var languageNames: JSONObject? = null
    private var picker: View? = null
    /** The row that had the remote before a picker opened; focus returns there on close. */
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
        val (bar, barStatus) = Screens.topBar(this, app, "System") { finish() }
        status = barStatus
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, app.theme.px(88f)))

        val scroll = ScrollView(this).apply {
            isFocusable = false
            isFocusableInTouchMode = false
        }
        rows = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(app.theme.gutter, app.theme.px(18f), app.theme.gutter, app.theme.px(40f))
        }
        scroll.addView(rows)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        Motion.enter(app.theme, root)
        load()
    }

    override fun onResume() {
        super.onResume()
        if (settings != null) paint()
    }

    private fun load(quiet: Boolean = false) {
        if (!quiet) status.text = "Reading the hub's settings…"
        api.settings { root, error ->
            if (isFinishing || isDestroyed) return@settings
            val s = root?.optJSONObject("settings")
            if (s == null) {
                status.text = error ?: "The hub did not send its settings"
                return@settings
            }
            settings = s
            languageNames = root.optJSONObject("languageNames") ?: JSONObject()
            if (!quiet) status.text = ""
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
        section("This hub", "Set once for the whole house. These are the values the hub is running with.")

        val name = s.optString("hubName").ifBlank { "Not set" }
        addRow("Name", name, "The word this hub shows on the network and its splash screen") {
            editName()
        }

        val chosen = selectedLanguages()
        addRow("Languages the addons search", languageSummary(chosen),
            "Which audio and subtitle languages the hub asks its addons for") {
            showLanguages()
        }

        val addons = s.optJSONObject("addons")
        val maxSources = addons?.optInt("maxSources", 0) ?: 0
        addRow("Copies per title", if (maxSources > 0) "$maxSources" else "Not set",
            "How many sources the hub ranks before it stops looking") {
            showMaxSources(maxSources)
        }
        val minSize = addons?.optInt("minSizeMB", 0) ?: 0
        addRow("Smallest file worth offering", sizeLabel(minSize),
            "Below this a copy is usually unwatchable, so it is dropped") {
            showMinSize(minSize)
        }

        val bands = addons?.optString("sizeFilter").orEmpty()
        if (bands.isNotBlank()) {
            rows.addView(Views.text(app, "meta", 17f, app.theme.ink3,
                "Size bands the addons are asked for: $bands", maxLines = 2),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = app.theme.px(6f)
                })
        }
    }

    private fun section(title: String, note: String) {
        Screens.section(this, app, rows, title)
        if (note.isNotBlank()) {
            rows.addView(Views.text(app, "meta", 17f, app.theme.ink3, note, maxLines = 2),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = app.theme.px(2f)
                    bottomMargin = app.theme.px(12f)
                })
        }
    }

    private fun addRow(label: String, value: String, note: String, action: () -> Unit) {
        Screens.addRow(this, app, rows, label, value, note, chevron = true, onClick = action)
    }

    // ---- values ------------------------------------------------------------------------------

    private fun selectedLanguages(): MutableList<String> {
        val out = ArrayList<String>()
        val arr = settings?.optJSONArray("languages") ?: return out
        for (i in 0 until arr.length()) {
            val v = arr.optString(i, "")
            if (v.isNotBlank() && v !in out) out.add(v)
        }
        return out
    }

    private fun langLabel(code: String): String =
        languageNames?.optString(code).orEmpty().ifBlank { code.uppercase() }

    private fun languageSummary(chosen: List<String>): String = when {
        chosen.isEmpty() -> "None — every copy allowed"
        chosen.size <= 2 -> chosen.joinToString(", ") { langLabel(it) }
        else -> "${chosen.size} chosen"
    }

    private fun languageOptions(): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        val names = languageNames ?: JSONObject()
        val keys = names.keys()
        while (keys.hasNext()) {
            val code = keys.next()
            if (code.isBlank()) continue
            out.add(code to names.optString(code, code).ifBlank { code })
        }
        // A legacy spelling may still be stored even though the hub now names the modern code once.
        // Keeping it in the list is the difference between showing it and silently dropping it on Save.
        for (code in selectedLanguages()) {
            if (out.none { it.first == code }) out.add(0, code to code.uppercase())
        }
        return out.sortedBy { it.second.lowercase() }
    }

    private fun sizeLabel(mb: Int): String = if (mb <= 0) "No floor" else "$mb MB"

    // ---- edits -------------------------------------------------------------------------------

    private fun editName() {
        startActivityForResult(
            Intent(this, TextEntryActivity::class.java)
                .putExtra(TextEntryActivity.EXTRA_TITLE, "Hub name")
                .putExtra(TextEntryActivity.EXTRA_VALUE, settings?.optString("hubName").orEmpty())
                .putExtra(TextEntryActivity.EXTRA_HINT, "How this hub introduces itself on the network"),
            REQ_NAME,
        )
    }

    private fun showLanguages() {
        val options = languageOptions()
        if (options.isEmpty()) {
            status.text = "The hub named no languages"
            return
        }
        showChecklist(
            "Languages the addons search",
            "The hub asks its addons for these audio and subtitle languages. No choice means no filter.",
            options,
            selectedLanguages().toSet(),
        ) { picked -> commit("Languages saved") { s -> s.put("languages", JSONArray(picked)) } }
    }

    private fun showMaxSources(current: Int) {
        val values = mutableListOf(1, 2, 4, 6, 8, 12, 16, 24)
        if (current > 0 && current !in values) {
            values.add(current)
            values.sort()
        }
        showPicker("Copies per title", values.map { it.toString() to it.toString() }, current.toString()) { value ->
            val n = value.toIntOrNull() ?: return@showPicker
            commit("Saved") { s ->
                val addons = s.optJSONObject("addons") ?: JSONObject().also { s.put("addons", it) }
                addons.put("maxSources", n)
            }
        }
    }

    private fun showMinSize(current: Int) {
        val values = mutableListOf(0, 50, 100, 200, 300, 500)
        if (current > 0 && current !in values) {
            values.add(current)
            values.sort()
        }
        showPicker("Smallest file", values.map { it.toString() to sizeLabel(it) }, current.toString()) { value ->
            val n = value.toIntOrNull() ?: return@showPicker
            commit("Saved") { s ->
                val addons = s.optJSONObject("addons") ?: JSONObject().also { s.put("addons", it) }
                addons.put("minSizeMB", n)
            }
        }
    }

    /**
     * A whole settings write, the way the web screen does it: the object it last read with one field
     * replaced. The hub merges it and deletes `lock`; `debrid.token` is never in this copy, so the stored
     * token is kept rather than erased.
     */
    private fun commit(note: String, mutate: (JSONObject) -> Unit) {
        val s = settings ?: return
        val copy = JSONObject(s.toString())
        mutate(copy)
        status.text = "Saving…"
        api.saveSettings(JSONObject().put("settings", copy)) { _, error ->
            if (isFinishing || isDestroyed) return@saveSettings
            if (error != null) {
                status.text = error
                return@saveSettings
            }
            status.text = note
            load(quiet = true)
            status.postDelayed({ if (!isFinishing) status.text = "" }, 2500)
        }
    }

    @Deprecated("Activity result API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_NAME || resultCode != RESULT_OK) return
        val name = data?.getStringExtra(TextEntryActivity.EXTRA_RESULT).orEmpty().trim()
        if (name.isBlank()) {
            status.text = "A hub needs a name"
            return
        }
        commit("Name saved") { s -> s.put("hubName", name) }
    }

    // ---- pickers -----------------------------------------------------------------------------

    /** One value out of a fixed list; the tick marks the value the hub is running with. */
    private fun showPicker(title: String, options: List<Pair<String, String>>, current: String, onPick: (String) -> Unit) {
        if (picker == null) pickerReturn = currentFocus
        closePicker()
        val layer = FrameLayout(this).apply {
            setBackgroundColor(Color.argb(190, 0, 0, 0))
            isClickable = true
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(app.theme.px(28f), app.theme.px(22f), app.theme.px(28f), app.theme.px(22f))
            background = Screens.modalFill(app)
        }
        panel.addView(Views.text(app, "li", 26f, app.theme.ink, title, bold = true),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = app.theme.px(12f)
            })
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val firstRows = ArrayList<View>()
        for ((value, label) in options) {
            val selected = value == current
            val row = Screens.pickerRow(this, app, label, selected) {
                closePicker()
                onPick(value)
            }
            list.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, app.theme.px(60f)).apply {
                topMargin = app.theme.px(4f)
            })
            firstRows.add(row)
        }
        panel.addView(list)
        val width = app.theme.px(620f)
        val height = (options.size * app.theme.px(64f) + app.theme.px(110f))
            .coerceAtMost(app.theme.px(700f))
        layer.addView(panel, FrameLayout.LayoutParams(width, height).apply { gravity = Gravity.CENTER })
        root().addView(layer, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        picker = layer
        val target = firstRows.getOrNull(options.indexOfFirst { it.first == current }.coerceAtLeast(0))
            ?: firstRows.firstOrNull()
        target?.post { target.requestFocus() }
    }

    /** A checklist committed together: walking past a wrong language costs nothing until Save. */
    private fun showChecklist(
        title: String,
        note: String,
        options: List<Pair<String, String>>,
        selected: Set<String>,
        onSave: (List<String>) -> Unit,
    ) {
        closePicker()
        val chosen = LinkedHashSet<String>(selected)
        val layer = FrameLayout(this).apply {
            setBackgroundColor(Color.argb(190, 0, 0, 0))
            isClickable = true
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(app.theme.px(28f), app.theme.px(22f), app.theme.px(28f), app.theme.px(20f))
            background = Screens.modalFill(app)
            elevation = app.theme.px(16f).toFloat()
        }
        panel.addView(Views.text(app, "li", 26f, app.theme.ink, title, bold = true))
        panel.addView(Views.text(app, "meta", 17f, app.theme.ink3, note, maxLines = 2),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = app.theme.px(4f)
                bottomMargin = app.theme.px(10f)
            })
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val firstRows = ArrayList<View>()
        for ((value, label) in options) {
            var on = value in chosen
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(app.theme.px(16f), 0, app.theme.px(16f), 0)
                isFocusable = true
                isClickable = true
            }
            // The tick, not the row background, carries the selection: Focus.attach keeps the row's
            // background for its ring, so a background flipped while focused would snap back on the way
            // out and leave the checklist disagreeing with itself.
            val mark = Views.text(app, "li", 22f,
                if (on) app.theme.color("panelValue", "#F2C33A") else app.theme.ink,
                if (on) "✓" else " ", bold = true, maxLines = 1)
            row.addView(mark, LinearLayout.LayoutParams(app.theme.px(34f), ViewGroup.LayoutParams.WRAP_CONTENT))
            row.addView(Views.text(app, "li", 22f, app.theme.ink, label, maxLines = 1),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.setOnClickListener {
                on = !on
                if (on) chosen.add(value) else chosen.remove(value)
                mark.text = if (on) "✓" else " "
                mark.setTextColor(if (on) app.theme.color("panelValue", "#F2C33A") else app.theme.ink)
            }
            Focus.attach(row, row, app, app.theme.px(8f), scale = false, elevate = false)
            list.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, app.theme.px(58f)).apply {
                topMargin = app.theme.px(4f)
            })
            firstRows.add(row)
        }
        val listHeight = options.size * app.theme.px(62f)
        if (listHeight > app.theme.px(500f)) {
            val scroll = ScrollView(this).apply {
                isVerticalScrollBarEnabled = false
                isFocusable = false
                isFocusableInTouchMode = false
            }
            scroll.addView(list)
            panel.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, app.theme.px(500f)))
        } else {
            panel.addView(list)
        }

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val save = button("Save") {
            closePicker()
            onSave(chosen.toList())
        }
        val cancel = button("Cancel") { closePicker() }
        buttons.addView(save, LinearLayout.LayoutParams(app.theme.px(150f), app.theme.px(54f)))
        buttons.addView(cancel, LinearLayout.LayoutParams(app.theme.px(150f), app.theme.px(54f)).apply {
            marginStart = app.theme.px(12f)
        })
        panel.addView(buttons, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = app.theme.px(14f)
        })

        layer.addView(panel, FrameLayout.LayoutParams(app.theme.px(760f), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER
        })
        root().addView(layer, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        picker = layer
        (firstRows.firstOrNull() ?: save).post {
            (firstRows.firstOrNull() ?: save).requestFocus()
        }
    }

    private fun button(label: String, action: () -> Unit): TextView {
        val b = Views.text(app, "li", 21f, app.theme.ink, label, bold = true, gravity = Gravity.CENTER)
        b.background = Views.rounded(app.theme.surface2, app.theme.px(9f))
        b.isFocusable = true
        b.isClickable = true
        b.setOnClickListener { action() }
        Focus.attach(b, b, app, app.theme.px(9f), scale = false, elevate = false)
        return b
    }

    private fun root(): ViewGroup = findViewById(android.R.id.content)

    private fun closePicker() {
        val layer = picker ?: return
        (layer.parent as? ViewGroup)?.removeView(layer)
        val wasOpen = picker != null
        picker = null
        if (wasOpen) {
            pickerReturn?.takeIf { it.isAttachedToWindow }?.requestFocus()
            pickerReturn = null
        }
    }

    override fun onBackPressed() {
        if (picker != null) { closePicker(); return }
        super.onBackPressed()
    }

    companion object {
        private const val REQ_NAME = 51
    }
}
