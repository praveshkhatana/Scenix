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
 * The household's Live TV source and genre filter. One playlist URL is what an owner can actually find
 * and paste; hiding a group removes a whole genre from every profile's wall at once, while the channels
 * themselves stay per-profile in [LiveChannelsActivity]. Both values live in `/app/v1/settings`, the same
 * document the web's channel manager writes, and the whole object is sent back so nothing else moves.
 */
class LiveSettingsActivity : Activity() {

    private lateinit var app: AppContext
    private lateinit var rows: LinearLayout
    private lateinit var status: TextView
    private var settings: JSONObject? = null
    private var groups: JSONArray? = null
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
        val (bar, barStatus) = Screens.topBar(this, app, "Live TV") { finish() }
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
        if (!quiet) status.text = "Reading the live settings…"
        api.settings { root, error ->
            if (isFinishing || isDestroyed) return@settings
            val s = root?.optJSONObject("settings")
            if (s == null) {
                status.text = error ?: "The hub did not send its settings"
                return@settings
            }
            settings = s
            groups = root.optJSONObject("live")?.optJSONArray("groups") ?: JSONArray()
            if (!quiet) status.text = ""
            paint()
        }
    }

    private fun paint() {
        rows.removeAllViews()
        val s = settings ?: return
        val live = s.optJSONObject("live")
        val playlist = live?.optString("playlist").orEmpty()

        Screens.section(this, app, rows, "The house playlist")
        rows.addView(Views.text(app, "meta", 17f, app.theme.ink3,
            "One address the hub reads for every profile. An empty address turns the Live TV row off.",
            maxLines = 2),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = app.theme.px(2f)
                bottomMargin = app.theme.px(12f)
            })

        addRow("Playlist address", if (playlist.isBlank()) "Not set" else playlist,
            "The M3U address this hub tunes for Live TV") {
            editPlaylist(playlist)
        }

        val hidden = ids(live?.optJSONArray("groupsHidden"))
        val all = groupOptions(hidden)
        addRow("Hidden groups", groupSummary(hidden, all.size),
            "Take a whole genre out of the channel wall for every profile") {
            if (all.isEmpty()) {
                status.text = "The hub has not published any groups"
            } else {
                showGroups(all, hidden)
            }
        }

        if (all.isEmpty()) {
            note("The hub lists no channel groups yet. They appear once a playlist publishes channels.")
        } else if (hidden.isEmpty()) {
            note("Every published group is visible. Hide one to keep its channels off the wall entirely.")
        } else {
            note("${hidden.size} of ${all.size} groups hidden. The per-profile lineup still chooses channels inside the visible groups.")
        }
    }

    private fun addRow(label: String, value: String, note: String, action: () -> Unit) {
        Screens.addRow(this, app, rows, label, value, note, chevron = true, onClick = action)
    }

    private fun note(text: String) {
        rows.addView(Views.text(app, "meta", 17f, app.theme.ink3, text, maxLines = 3),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = app.theme.px(6f)
            })
    }

    // ---- values ------------------------------------------------------------------------------

    private fun ids(arr: JSONArray?): MutableList<String> {
        val out = ArrayList<String>()
        if (arr == null) return out
        for (i in 0 until arr.length()) {
            val v = arr.optString(i, "")
            if (v.isNotBlank() && v !in out) out.add(v)
        }
        return out
    }

    /**
     * The published groups plus any stored hidden group the playlist no longer publishes. Omitting the
     * latter would make a stale choice impossible to remove from the television.
     */
    private fun groupOptions(hidden: List<String>): List<String> {
        val out = ArrayList<String>()
        val published = groups ?: JSONArray()
        for (i in 0 until published.length()) {
            val g = published.optString(i, "")
            if (g.isNotBlank() && g !in out) out.add(g)
        }
        for (g in hidden) if (g !in out) out.add(g)
        return out.sortedBy { it.lowercase() }
    }

    private fun groupSummary(hidden: List<String>, total: Int): String = when {
        hidden.isEmpty() -> "None"
        hidden.size <= 2 -> hidden.joinToString(", ")
        else -> "$total groups · ${hidden.size} hidden"
    }

    // ---- edits -------------------------------------------------------------------------------

    private fun editPlaylist(current: String) {
        startActivityForResult(
            Intent(this, TextEntryActivity::class.java)
                .putExtra(TextEntryActivity.EXTRA_TITLE, "Live TV playlist")
                .putExtra(TextEntryActivity.EXTRA_VALUE, current)
                .putExtra(TextEntryActivity.EXTRA_HINT, "https://…/playlist.m3u — empty turns Live TV off"),
            REQ_PLAYLIST,
        )
    }

    private fun showGroups(all: List<String>, hidden: List<String>) {
        showChecklist(
            "Hidden groups",
            "Tick a genre to keep it off every profile's channel wall. Back cancels; Save commits them together.",
            all.map { it to it },
            hidden.toSet(),
        ) { picked -> commit("Groups saved") { s -> live(s).put("groupsHidden", JSONArray(picked)) } }
    }

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

    private fun live(s: JSONObject): JSONObject =
        s.optJSONObject("live") ?: JSONObject().also { s.put("live", it) }

    @Deprecated("Activity result API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PLAYLIST || resultCode != RESULT_OK) return
        val url = data?.getStringExtra(TextEntryActivity.EXTRA_RESULT).orEmpty().trim()
        commit(if (url.isBlank()) "Live TV off" else "Playlist saved") { s ->
            live(s).put("playlist", url)
        }
    }

    // ---- pickers -----------------------------------------------------------------------------

    /** A checklist committed together: walking past a wrong group costs nothing until Save. */
    private fun showChecklist(
        title: String,
        note: String,
        options: List<Pair<String, String>>,
        selected: Set<String>,
        onSave: (List<String>) -> Unit,
    ) {
        closePicker()
        val chosen = LinkedHashSet<String>(selected)
        pickerReturn = currentFocus ?: pickerReturn
        val layer = FrameLayout(this).apply {
            setBackgroundColor(Screens.dimColor(app))
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
        picker = null
        pickerReturn?.requestFocus()
        pickerReturn = null
    }

    override fun onBackPressed() {
        if (picker != null) { closePicker(); return }
        super.onBackPressed()
    }

    companion object {
        private const val REQ_PLAYLIST = 61
    }
}
