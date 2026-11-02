package com.scenix.tv

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
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
 * The shelves the owner makes for themselves. Home rows come from the hub's plugins; a category is the
 * other half of the same idea — a provider/catalogue (optionally one genre) this hub already carries,
 * named by the owner and placed after the plugin rows.
 *
 * The screen is the television counterpart of the web categories builder: every choice comes from
 * `/app/v1/settings` → `catalogOptions`, so no provider or genre is written down here. Each edit sends
 * the whole settings copy it read back through `PUT /app/v1/settings` with `categories` replaced, then
 * reloads and repaints, so the list always shows what the hub will draw on the next Home load.
 */
class CategoriesActivity : Activity() {

    private lateinit var app: AppContext
    private lateinit var root: FrameLayout
    private lateinit var rows: LinearLayout
    private lateinit var status: TextView
    private val api by lazy { BrowseApi(applicationContext, (application as ScenixApp).prefs) }

    private var settings: JSONObject? = null
    private var options: JSONArray? = null
    private var categories: MutableList<JSONObject> = ArrayList()

    /** The add ladder's choice, kept across repaints until a catalogue or the hub changes under it. */
    private var providerId: String? = null
    private var catalogId: String? = null
    private var genre = ""

    private var picker: View? = null
    /** The row that had the remote before a picker opened; focus returns there on close. */
    private var pickerReturn: View? = null
    private var saving = false
    private var focusTag: String? = null
    private var renameAt = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        app = (application as ScenixApp).ctx

        root = FrameLayout(this).apply { setBackgroundColor(app.theme.bg) }
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(app.theme.bg)
        }
        page.addView(topBar(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, app.theme.px(88f)))
        val scroll = ScrollView(this).apply {
            isFocusable = false
            isFocusableInTouchMode = false
        }
        rows = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(app.theme.gutter, app.theme.px(18f), app.theme.gutter, app.theme.px(48f))
        }
        scroll.addView(rows)
        page.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(page, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        setContentView(root)
        load()
    }

    private fun topBar(): View {
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
        bar.addView(Views.text(app, "li", 26f, app.theme.ink, "Categories", bold = true).apply {
            setPadding(app.theme.px(20f), 0, 0, 0)
        })
        bar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        status = Views.text(app, "meta", 19f, app.theme.ink2, "", maxLines = 1)
        bar.addView(status)
        return bar
    }

    // ---- reads ---------------------------------------------------------------------------------

    private fun load(quiet: Boolean = false) {
        if (!quiet) status.text = "Reading the hub's categories…"
        api.settings { answer, error ->
            if (isFinishing || isDestroyed) return@settings
            val s = answer?.optJSONObject("settings")
            if (s == null) {
                if (!quiet) status.text = error ?: "The hub did not send its settings"
                return@settings
            }
            settings = s
            options = answer.optJSONArray("catalogOptions") ?: JSONArray()
            categories = readCategories(s)
            if (!quiet) status.text = ""
            paint()
        }
    }

    private fun readCategories(s: JSONObject): MutableList<JSONObject> {
        val out = ArrayList<JSONObject>()
        val arr = s.optJSONArray("categories") ?: return out
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(JSONObject(o.toString()))
        }
        return out
    }

    // ---- catalogue choices ---------------------------------------------------------------------

    private fun providers(): List<String> {
        val out = ArrayList<String>()
        val list = options ?: return out
        for (i in 0 until list.length()) {
            val p = list.optJSONObject(i)?.optString("provider").orEmpty()
            if (p.isNotBlank() && p !in out) out.add(p)
        }
        return out
    }

    private fun optionsFor(provider: String?): List<JSONObject> {
        val out = ArrayList<JSONObject>()
        val list = options ?: return out
        for (i in 0 until list.length()) {
            val o = list.optJSONObject(i) ?: continue
            if (o.optString("provider") == provider) out.add(o)
        }
        return out
    }

    private fun findOption(provider: String, catalog: String): JSONObject? =
        optionsFor(provider).firstOrNull { it.optString("catalog") == catalog }

    private fun selectedOption(): JSONObject? = findOption(providerId.orEmpty(), catalogId.orEmpty())

    private fun providerName(id: String?): String {
        val list = options ?: return id.orEmpty()
        for (i in 0 until list.length()) {
            val o = list.optJSONObject(i) ?: continue
            if (o.optString("provider") == id) {
                return o.optString("providerName").ifBlank { id.orEmpty() }
            }
        }
        return id.orEmpty()
    }

    private fun rawString(o: JSONObject?, key: String): String {
        val v = o?.opt(key) ?: return ""
        return if (v == JSONObject.NULL) "" else v.toString()
    }

    /** A catalogue's own name, plus its kind when the name alone would be ambiguous (two "Popular"s). */
    private fun catalogLabel(o: JSONObject?): String {
        val name = rawString(o, "name").ifBlank { rawString(o, "catalog") }
        val type = rawString(o, "type")
        return if (type.isBlank()) name else "$name ($type)"
    }

    private fun genresOf(o: JSONObject?): List<String> {
        val out = ArrayList<String>()
        val arr = o?.optJSONArray("genres") ?: return out
        for (i in 0 until arr.length()) {
            val g = arr.optString(i, "").trim()
            if (g.isNotBlank() && g !in out) out.add(g)
        }
        return out
    }

    /** Keep the add ladder valid after a catalogue is disabled or a different hub answers. */
    private fun syncChoice() {
        val ids = providers()
        if (providerId !in ids) providerId = ids.firstOrNull()
        val mine = optionsFor(providerId)
        if (mine.none { it.optString("catalog") == catalogId }) {
            catalogId = mine.firstOrNull()?.optString("catalog")
        }
        val opt = selectedOption()
        if (opt == null || genre !in genresOf(opt)) genre = ""
    }

    private fun defaultTitle(opt: JSONObject): String {
        val base = (genre.ifBlank { rawString(opt, "name") }.ifBlank { rawString(opt, "catalog") })
            .trim().take(60).ifBlank { "Category" }
        val used = categories.map { it.optString("title").lowercase() }.toSet()
        if (base.lowercase() !in used) return base
        var n = 2
        while ((base + " " + n).lowercase() in used) n++
        return base + " " + n
    }

    // ---- paint ---------------------------------------------------------------------------------

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
        var first: View? = null

        section("Add a category",
            "Choose a catalogue this hub already carries, optionally one genre it publishes, then name the shelf.")
        val catalogOptions = options
        if (catalogOptions == null || catalogOptions.length() == 0) {
            rows.addView(Views.text(app, "meta", 18f, app.theme.ink3,
                "This hub is not publishing any catalogue right now. Install or enable an add-on in Settings, then come back.",
                maxLines = 2))
        } else {
            syncChoice()
            val opt = selectedOption()
            first = addRow("Source", providerName(providerId), "whose catalogue this shelf will read", "add:source") {
                pickSource()
            }
            if (opt != null) {
                addRow("Catalogue", catalogLabel(opt), "which list inside it", "add:catalog") {
                    pickCatalogue()
                }
                val genres = genresOf(opt)
                if (genres.isNotEmpty()) {
                    addRow("Genre", genre.ifBlank { "Any genre" }, "leave it on Any genre to take the whole catalogue", "add:genre") {
                        pickGenre()
                    }
                }
                val parts = ArrayList<String>()
                parts.add(providerName(providerId))
                parts.add(catalogLabel(opt))
                if (genre.isNotBlank()) parts.add(genre)
                addRow("Add this category", parts.filter { it.isNotBlank() }.joinToString(" · "),
                    "You choose its name on the next screen", "add:go") { beginAdd() }
            }
        }

        section("Your categories",
            if (categories.isEmpty()) "Nothing yet — the shelves you add appear after the hub's own rows."
            else {
                val shown = categories.count { it.optBoolean("enabled", true) }
                "$shown of ${categories.size} on Home · they follow the hub's own rows, in this order."
            })

        categories.forEachIndexed { at, c ->
            val title = c.optString("title").ifBlank {
                c.optString("genre").ifBlank { c.optString("catalog") }.ifBlank { "Category" }
            }
            val on = c.optBoolean("enabled", true)
            addRow(title, categoryBits(c), if (on) "On Home" else "Hidden", "cat:$at") {
                showCategoryActions(at)
            }
        }

        val wanted = focusTag?.let { rows.findViewWithTag<View>(it) }
            ?: first
            ?: firstFocusable()
        if (wanted != null && wanted.isFocusable) {
            wanted.post { if (!isFinishing && !isDestroyed) wanted.requestFocus() }
        }
    }

    /** Used only when a repaint left no tagged row behind: the first ring stop the page can offer. */
    private fun firstFocusable(): View? {
        for (i in 0 until rows.childCount) {
            val v = rows.getChildAt(i)
            if (v.isFocusable) return v
        }
        return null
    }

    private fun section(title: String, note: String) {
        rows.addView(Views.text(app, "li", 26f, app.theme.ink, title, bold = true),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        rows.addView(Views.text(app, "meta", 17f, app.theme.ink3, note, maxLines = 2),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = app.theme.px(2f)
                bottomMargin = app.theme.px(12f)
            })
    }

    private fun addRow(label: String, value: String, note: String, focusKey: String, action: () -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(app.theme.px(24f), 0, app.theme.px(24f), 0)
            background = Views.rounded(app.theme.surface, app.theme.px(10f))
            isFocusable = true
            isClickable = true
            tag = focusKey
            setOnClickListener { action() }
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(Views.text(app, "li", 24f, app.theme.ink, label, bold = true, maxLines = 1))
        if (note.isNotBlank()) {
            texts.addView(Views.text(app, "meta", 16f, app.theme.ink3, note, maxLines = 1),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = app.theme.px(2f)
                })
        }
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (value.isNotBlank()) {
            row.addView(Views.text(app, "meta", 20f, app.theme.ink2, value, maxLines = 1, gravity = Gravity.END))
        }
        Focus.attach(row, row, app, app.theme.px(10f), scale = false, elevate = false)
        rows.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, app.theme.px(88f)).apply {
            bottomMargin = app.theme.px(10f)
        })
        return row
    }

    private fun repaint(tag: String?) {
        focusTag = tag
        paint()
    }

    /** What a shelf reads: the source, the list, the genre, and whether the hub still publishes it. */
    private fun categoryBits(c: JSONObject): String {
        val provider = c.optString("provider")
        val catalog = c.optString("catalog")
        val opt = findOption(provider, catalog)
        val bits = ArrayList<String>()
        bits.add(if (opt != null) providerName(provider) else provider)
        bits.add(if (opt != null) catalogLabel(opt) else catalog)
        if (c.optString("genre").isNotBlank()) bits.add(c.optString("genre"))
        if (opt == null) bits.add("not published right now")
        return bits.filter { it.isNotBlank() }.joinToString(" · ")
    }

    // ---- writing -------------------------------------------------------------------------------

    /**
     * The whole settings copy this screen read, with `categories` replaced by the edited list. The hub
     * merges it and strips `lock`; the Debrid token is never in this copy, so the stored one survives.
     * Ids already assigned by the hub are kept, a new shelf leaves its id to the hub's normalizer, and
     * `order` is renumbered from the list's own position.
     */
    private fun save(next: List<JSONObject>, note: String, focus: String?) {
        if (saving) return
        val s = settings ?: return
        saving = true
        status.text = "Saving…"
        focusTag = focus
        val copy = JSONObject(s.toString())
        val arr = JSONArray()
        next.forEachIndexed { i, item ->
            val o = JSONObject(item.toString())
            o.put("order", i)
            arr.put(o)
        }
        copy.put("categories", arr)
        api.saveSettings(JSONObject().put("settings", copy)) { answer, error ->
            saving = false
            if (isFinishing || isDestroyed) return@saveSettings
            if (error != null) {
                status.text = error
                return@saveSettings
            }
            status.text = note
            // The PUT already answers the merged settings; paint from it so a slow reload cannot leave
            // the list showing a shelf the hub just refused. The GET after it refreshes catalogOptions.
            answer?.optJSONObject("settings")?.let { saved ->
                settings = saved
                categories = readCategories(saved)
                paint()
            }
            load(quiet = true)
            rows.postDelayed({ if (!isFinishing) status.text = "" }, 2500)
        }
    }

    private fun beginAdd() {
        val opt = selectedOption() ?: run {
            status.text = "Pick a source first"
            return
        }
        startActivityForResult(
            Intent(this, TextEntryActivity::class.java)
                .putExtra(TextEntryActivity.EXTRA_TITLE, "Name this category")
                .putExtra(TextEntryActivity.EXTRA_VALUE, defaultTitle(opt))
                .putExtra(TextEntryActivity.EXTRA_HINT, "The name this shelf carries on the home screen")
                .putExtra(TextEntryActivity.EXTRA_PLAIN_TEXT, true),
            REQ_ADD,
        )
    }

    private fun finishAdd(title: String) {
        val opt = selectedOption() ?: return
        val entry = JSONObject()
            .put("title", title.trim().take(60))
            .put("provider", opt.optString("provider"))
            .put("catalog", opt.optString("catalog"))
            .put("genre", genre)
            .put("enabled", true)
            .put("order", categories.size)
        val next = copyList()
        next.add(entry)
        save(next, "${entry.optString("title")} added to Home", "add:go")
    }

    // ---- category actions ----------------------------------------------------------------------

    private fun showCategoryActions(at: Int) {
        val c = categories.getOrNull(at) ?: return
        val title = c.optString("title").ifBlank {
            c.optString("genre").ifBlank { c.optString("catalog") }.ifBlank { "Category" }
        }
        val on = c.optBoolean("enabled", true)
        val actions = ArrayList<Pair<String, String>>()
        actions.add("toggle" to (if (on) "Hide \"$title\" from Home" else "Show \"$title\" on Home"))
        if (at > 0) actions.add("earlier" to "Move \"$title\" earlier")
        if (at < categories.size - 1) actions.add("later" to "Move \"$title\" later")
        actions.add("rename" to "Rename \"$title\"")
        actions.add("delete" to "Delete \"$title\"")
        actions.add("back" to "Back")
        showPicker(title, actions, "") { action ->
            when (action) {
                "toggle" -> {
                    val next = copyList()
                    next[at].put("enabled", !on)
                    save(next, "$title " + if (on) "hidden from Home" else "shown on Home", "cat:$at")
                }
                "earlier", "later" -> {
                    val next = copyList()
                    val moved = next.removeAt(at)
                    val to = if (action == "earlier") at - 1 else at + 1
                    next.add(to, moved)
                    save(next, "$title moved", "cat:$to")
                }
                "rename" -> beginRename(at)
                "delete" -> confirmDelete(at, title)
            }
        }
    }

    private fun beginRename(at: Int) {
        val c = categories.getOrNull(at) ?: return
        renameAt = at
        startActivityForResult(
            Intent(this, TextEntryActivity::class.java)
                .putExtra(TextEntryActivity.EXTRA_TITLE, "Rename category")
                .putExtra(TextEntryActivity.EXTRA_VALUE, c.optString("title"))
                .putExtra(TextEntryActivity.EXTRA_HINT, "The name the shelf carries on the home screen")
                .putExtra(TextEntryActivity.EXTRA_PLAIN_TEXT, true),
            REQ_RENAME,
        )
    }

    private fun finishRename(title: String) {
        val at = renameAt
        renameAt = -1
        if (at !in categories.indices) return
        if (title.isBlank()) {
            status.text = "Give the shelf a name first"
            return
        }
        val next = copyList()
        next[at].put("title", title.trim().take(60))
        save(next, "Category renamed", "cat:$at")
    }

    private fun confirmDelete(at: Int, title: String) {
        showPicker("Delete \"$title\"?", listOf(
            "keep" to "Keep it",
            "delete" to "Delete this category",
        ), "keep", setOf("delete")) { action ->
            if (action != "delete") return@showPicker
            val next = copyList()
            next.removeAt(at)
            val focus = if (next.isEmpty()) "add:source" else "cat:" + at.coerceAtMost(next.size - 1)
            save(next, "$title removed", focus)
        }
    }

    private fun copyList(): MutableList<JSONObject> {
        val out = ArrayList<JSONObject>(categories.size)
        for (c in categories) out.add(JSONObject(c.toString()))
        return out
    }

    // ---- picker --------------------------------------------------------------------------------

    private fun pickSource() {
        val ids = providers()
        if (ids.isEmpty()) return
        showPicker("Source", ids.map { it to providerName(it) }, providerId.orEmpty()) { id ->
            providerId = id
            catalogId = null
            genre = ""
            repaint("add:catalog")
        }
    }

    private fun pickCatalogue() {
        val mine = optionsFor(providerId)
        if (mine.isEmpty()) return
        showPicker("Catalogue", mine.map { it.optString("catalog") to catalogLabel(it) }, catalogId.orEmpty()) { cat ->
            catalogId = cat
            genre = ""
            val target = if (genresOf(selectedOption()).isNotEmpty()) "add:genre" else "add:go"
            repaint(target)
        }
    }

    private fun pickGenre() {
        val genres = genresOf(selectedOption())
        if (genres.isEmpty()) return
        val values = ArrayList<Pair<String, String>>()
        values.add("" to "Any genre")
        for (g in genres) values.add(g to g)
        showPicker("Genre", values, genre) { picked ->
            genre = picked
            repaint("add:go")
        }
    }

    private fun showPicker(
        title: String,
        options: List<Pair<String, String>>,
        current: String,
        danger: Set<String> = emptySet(),
        onPick: (String) -> Unit,
    ) {
        if (picker == null) pickerReturn = currentFocus
        closePicker()
        val layer = FrameLayout(this).apply {
            setBackgroundColor(Color.argb(190, 0, 0, 0))
            isClickable = true
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(app.theme.px(28f), app.theme.px(22f), app.theme.px(28f), app.theme.px(22f))
            background = Views.rounded(app.theme.color("panelScrim", "#E6000000"), app.theme.px(12f),
                app.theme.px(1f), app.theme.line)
            elevation = app.theme.px(16f).toFloat()
        }
        panel.addView(Views.text(app, "li", 26f, app.theme.ink, title, bold = true),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = app.theme.px(12f)
            })
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val focusables = ArrayList<View>()
        for ((value, label) in options) {
            val selected = if (current.isBlank()) value.isBlank() else value == current
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(app.theme.px(16f), 0, app.theme.px(16f), 0)
                background = Views.rounded(if (selected) app.theme.surface2 else Color.TRANSPARENT, app.theme.px(8f))
                isFocusable = true
                isClickable = true
                setOnClickListener {
                    closePicker()
                    onPick(value)
                }
            }
            val tick = if (selected) "✓" else " "
            val color = when {
                value in danger -> app.theme.color("warn", "#F2C33A")
                selected -> app.theme.color("panelValue", "#F2C33A")
                else -> app.theme.ink
            }
            row.addView(Views.text(app, "li", 22f, color, "$tick  $label", bold = selected, maxLines = 1),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            Focus.attach(row, row, app, app.theme.px(8f), scale = false, elevate = false)
            list.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, app.theme.px(60f)).apply {
                topMargin = app.theme.px(4f)
            })
            focusables.add(row)
        }
        // A genre list can run past the panel; the rows inside still take the D-pad ring while the
        // scroll view itself never does.
        val listHeight = options.size * app.theme.px(64f)
        if (listHeight > app.theme.px(560f)) {
            val scroll = ScrollView(this).apply {
                isVerticalScrollBarEnabled = false
                isFocusable = false
                isFocusableInTouchMode = false
            }
            scroll.addView(list)
            panel.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, app.theme.px(560f)))
        } else {
            panel.addView(list)
        }
        val width = app.theme.px(620f)
        val height = (listHeight.coerceAtMost(app.theme.px(560f)) + app.theme.px(110f))
            .coerceAtMost(app.theme.px(700f))
        layer.addView(panel, FrameLayout.LayoutParams(width, height).apply { gravity = Gravity.CENTER })
        root.addView(layer, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        picker = layer
        val target = focusables.getOrNull(options.indexOfFirst { it.first == current }) ?: focusables.firstOrNull()
        target?.post { target.requestFocus() }
    }

    private fun closePicker() {
        picker?.let { root.removeView(it) }
        val wasOpen = picker != null
        picker = null
        if (wasOpen) {
            pickerReturn?.takeIf { it.isAttachedToWindow }?.requestFocus()
            pickerReturn = null
        }
    }

    // ---- results / back ------------------------------------------------------------------------

    @Deprecated("Activity result API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val text = data?.getStringExtra(TextEntryActivity.EXTRA_RESULT).orEmpty().trim()
        when (requestCode) {
            REQ_ADD -> {
                if (text.isNotBlank()) finishAdd(text) else status.text = "Give the shelf a name first"
            }
            REQ_RENAME -> finishRename(text)
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_DOWN && picker != null) {
            closePicker()
            // The row that opened the picker is the last focused view; Android restores it.
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    companion object {
        private const val REQ_ADD = 61
        private const val REQ_RENAME = 62
    }
}
