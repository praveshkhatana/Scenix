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
import com.scenix.tv.core.Focus
import com.scenix.tv.core.Views
import org.json.JSONArray
import org.json.JSONObject

/**
 * One profile's Live TV lineup. With no lineup the wall shows every channel the rules allow; adding
 * channels turns it into a personal wall in the order they were added. This is per-profile: one person
 * can keep a news/music lineup while another keeps cartoons.
 *
 * The channel catalog comes from /app/v1/settings (`live.channels`), the same list the web's channel
 * manager uses; the lineup is written to the profile's prefs (`live.only` / `live.order`), never to the
 * household's settings.
 */
class LiveChannelsActivity : Activity() {

    private lateinit var app: AppContext
    private lateinit var profileId: String
    private lateinit var rows: LinearLayout
    private lateinit var status: TextView
    private var channels: JSONArray? = null
    private var only: MutableList<String> = ArrayList()
    private var order: MutableList<String> = ArrayList()
    private var picker: View? = null
    private val api by lazy { BrowseApi(applicationContext, (application as ScenixApp).prefs) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        app = (application as ScenixApp).ctx
        profileId = intent?.getStringExtra(EXTRA_PROFILE).orEmpty()
        if (profileId.isBlank()) { finish(); return }

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
        bar.addView(Views.text(app, "li", 26f, app.theme.ink, "Live TV channels", bold = true).apply {
            setPadding(app.theme.px(20f), 0, 0, 0)
        })
        bar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        status = Views.text(app, "meta", 19f, app.theme.ink2, "", maxLines = 1)
        bar.addView(status)
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, app.theme.px(88f)))

        val scroll = ScrollView(this).apply {
            isFocusable = false
            isFocusableInTouchMode = false
        }
        rows = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(app.theme.gutter, app.theme.px(10f), app.theme.gutter, app.theme.px(40f))
        }
        scroll.addView(rows)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        load()
    }

    override fun onResume() {
        super.onResume()
        if (channels != null) paint()
    }

    private fun load() {
        status.text = "Reading channels…"
        var pending = 2
        fun done() {
            pending--
            if (pending == 0) { status.text = ""; paint() }
        }
        api.settings { root, error ->
            if (isFinishing || isDestroyed) return@settings
            channels = root?.optJSONObject("live")?.optJSONArray("channels")
            if (channels == null && error != null) status.text = error
            done()
        }
        api.prefs(profileId) { p, _ ->
            if (isFinishing || isDestroyed) return@prefs
            val live = p?.optJSONObject("live")
            only = ids(live?.optJSONArray("only"))
            order = ids(live?.optJSONArray("order"))
            done()
        }
    }

    private fun ids(arr: JSONArray?): MutableList<String> {
        val out = ArrayList<String>()
        if (arr == null) return out
        for (i in 0 until arr.length()) {
            val v = arr.optString(i, "")
            if (v.isNotBlank() && v !in out) out.add(v)
        }
        return out
    }

    private fun paint() {
        rows.removeAllViews()
        val list = channels ?: return
        rows.addView(Views.text(app, "meta", 18f, app.theme.ink3,
            if (only.isEmpty()) "No personal lineup yet: the wall shows every channel the profile may watch. Add a channel to make it this profile's own wall."
            else "${only.size} channel${if (only.size == 1) "" else "s"} in this profile's lineup, in the order below.",
            maxLines = 3),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = app.theme.px(10f)
            })
        for (i in 0 until list.length()) {
            val ch = list.optJSONObject(i) ?: continue
            val id = ch.optString("identity")
            if (id.isBlank()) continue
            if (ch.optBoolean("enabled", true).not()) continue
            val name = ch.optString("name").ifBlank { id }
            val group = ch.optString("group")
            val number = ch.optInt("number", 0)
            val added = id in only
            val label = (if (number > 0) "$number  ·  " else "") + name
            val note = listOf(group, if (added) "In lineup" else "").filter { it.isNotBlank() }.joinToString(" · ")
            addRow(label, note, if (added) "In lineup" else "Add") { showActions(ch, id, name, added) }
        }
    }

    private fun addRow(label: String, note: String, value: String, action: () -> Unit) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(app.theme.px(18f), 0, app.theme.px(18f), 0)
            background = Views.rounded(app.theme.surface, app.theme.px(8f))
            isFocusable = true
            isClickable = true
            setOnClickListener { action() }
        }
        val words = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        words.addView(Views.text(app, "li", 21f, app.theme.ink, label, bold = true, maxLines = 1))
        if (note.isNotBlank()) {
            words.addView(Views.text(app, "meta", 15f, app.theme.ink3, note, maxLines = 1),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        row.addView(words, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(Views.text(app, "meta", 17f, app.theme.ink2, value, maxLines = 1, gravity = Gravity.END))
        Focus.attach(row, row, app, app.theme.px(8f), scale = false, elevate = false)
        rows.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, app.theme.px(66f)).apply {
            bottomMargin = app.theme.px(4f)
        })
    }

    private fun showActions(ch: JSONObject, id: String, name: String, added: Boolean) {
        val options = ArrayList<Pair<String, String>>()
        if (added) {
            options.add("remove" to "Remove $name from my lineup")
            val at = order.indexOf(id)
            if (at > 0) options.add("earlier" to "Move $name earlier")
            if (at in 0 until order.size - 1) options.add("later" to "Move $name later")
        } else {
            options.add("add" to "Add $name to my lineup")
        }
        options.add("back" to "Back")
        showPicker(name, options) { action ->
            when (action) {
                "add" -> {
                    if (id !in only) only.add(id)
                    if (id !in order) order.add(id)
                    save()
                }
                "remove" -> {
                    only.remove(id)
                    order.remove(id)
                    save()
                }
                "earlier", "later" -> {
                    val at = order.indexOf(id)
                    if (at >= 0) {
                        val to = order.toMutableList()
                        to.removeAt(at)
                        to.add(if (action == "earlier") (at - 1).coerceAtLeast(0) else (at + 1).coerceAtMost(to.size), id)
                        order = to
                        only = to.filter { it in only }.toMutableList()
                        save()
                    }
                }
            }
        }
    }

    private fun save() {
        status.text = "Saving…"
        val live = JSONObject()
            .put("only", JSONArray(only))
            .put("order", JSONArray(order))
        api.savePrefs(profileId, JSONObject().put("live", live)) { p, error ->
            if (isFinishing || isDestroyed) return@savePrefs
            if (error != null) {
                status.text = error
                return@savePrefs
            }
            val l = p?.optJSONObject("live")
            only = ids(l?.optJSONArray("only"))
            order = ids(l?.optJSONArray("order"))
            status.text = "Saved"
            paint()
            status.postDelayed({ if (!isFinishing) status.text = "" }, 2500)
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
        panel.addView(Views.text(app, "li", 24f, app.theme.ink, title, bold = true, maxLines = 2))
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
            row.addView(Views.text(app, "li", 20f, app.theme.ink, label, maxLines = 1),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            Focus.attach(row, row, app, app.theme.px(8f), scale = false, elevate = false)
            list.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, app.theme.px(56f)))
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

    override fun onBackPressed() {
        if (picker != null) { closePicker(); return }
        super.onBackPressed()
    }

    companion object {
        const val EXTRA_PROFILE = "profile"
    }
}
