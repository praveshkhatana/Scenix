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

/** The hub's installed sources, switchable and removable, with one native URL field to add another. */
class AddonsActivity : Activity() {

    private lateinit var app: AppContext
    private lateinit var rows: LinearLayout
    private lateinit var status: TextView
    private var installed: JSONArray? = null
    private var picker: View? = null
    // The row that opened the last picker; it takes focus back when the picker
    // closes, so the remote never lands on the page's back control by accident.
    private var pickerReturn: View? = null
    private var renameAddon: String? = null
    private var renameCatalog: String? = null
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
        val (bar, barStatus) = Screens.topBar(this, app, "Add-ons") { finish() }
        status = barStatus
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, app.theme.px(88f)))

        rows = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(app.theme.gutter, app.theme.px(18f), app.theme.gutter, app.theme.px(40f))
        }
        // A hub with more than a handful of add-ons outgrows the screen; unscrolled, the trailing rows
        // sit clipped and focus walks into a half-visible row it can never bring up. ScrollView passes
        // child focus into view on its own, exactly as Settings does.
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
            // Entering a screen lands on its first content stop, never on chrome: the back chevron
            // is reached by walking UP from the rows, like every other list screen.
            rows.getChildAt(0)?.post { rows.getChildAt(0).requestFocus() }
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
        Screens.addRow(this, app, rows, label, value, note, chevron = true, onClick = action)
    }

    private fun showActions(ad: JSONObject, name: String, on: Boolean, native: Boolean) {
        val options = ArrayList<Pair<String, String>>()
        options.add("toggle" to (if (on) "Turn $name off" else "Turn $name on"))
        if (!native) {
            val cats = ad.optJSONArray("catalogs")
            if (cats != null && cats.length() > 0) options.add("rename" to "Rename catalogue tiles")
            options.add("remove" to "Remove $name")
        }
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
                "rename" -> {
                    val cats = ad.optJSONArray("catalogs") ?: return@showPicker
                    val choices = ArrayList<Pair<String, String>>()
                    for (i in 0 until cats.length()) {
                        val c = cats.optJSONObject(i) ?: continue
                        val id = c.optString("id")
                        if (id.isBlank()) continue
                        choices.add(id to c.optString("name", id))
                    }
                    if (choices.isEmpty()) return@showPicker
                    showPicker("Rename which shelf?", choices) { catalogId ->
                        val current = choices.firstOrNull { it.first == catalogId }?.second.orEmpty()
                        renameAddon = ad.optString("id")
                        renameCatalog = catalogId
                        startActivityForResult(
                            Intent(this, TextEntryActivity::class.java)
                                .putExtra(TextEntryActivity.EXTRA_TITLE, "Rename " + current)
                                .putExtra(TextEntryActivity.EXTRA_VALUE, current)
                                .putExtra(TextEntryActivity.EXTRA_HINT, "The name this shelf wears on Home"),
                            REQ_CATALOG_NAME,
                        )
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
        pickerReturn?.requestFocus()
        pickerReturn = null
    }

    @Deprecated("Activity result API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_CATALOG_NAME) {
            if (resultCode != RESULT_OK) return
            val name = data?.getStringExtra(TextEntryActivity.EXTRA_RESULT).orEmpty().trim()
            val addon = renameAddon ?: return
            val catalog = renameCatalog ?: return
            if (name.isBlank()) return
            status.text = "Saving…"
            val names = JSONObject().put("$addon:$catalog", name.take(60))
            api.saveSettings(JSONObject().put("settings", JSONObject().put("addonCatalogNames", names))) { _, error ->
                if (isFinishing || isDestroyed) return@saveSettings
                if (error != null) {
                    status.text = error
                    return@saveSettings
                }
                status.text = "Renamed. It shows on Home next time."
            }
            return
        }
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
        private const val REQ_CATALOG_NAME = 42
    }
}
