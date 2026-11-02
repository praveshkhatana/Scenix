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
import com.scenix.tv.core.Prefs
import com.scenix.tv.core.Theme
import com.scenix.tv.core.Updates
import com.scenix.tv.core.Views
import org.json.JSONArray
import org.json.JSONObject

/**
 * The television's own settings page. It covers the preferences that change what a D-pad viewer
 * hears and sees — the language order, the quality ceiling — and the household's people, add-ons,
 * packs and lock. Reads and writes the same `/app/v1` contract the web page uses, so the two
 * surfaces cannot disagree.
 */
class SettingsActivity : Activity() {

    private lateinit var app: AppContext
    private lateinit var profileId: String
    private lateinit var root: FrameLayout
    private lateinit var page: LinearLayout
    private lateinit var rows: LinearLayout
    private lateinit var status: TextView
    private val api by lazy { BrowseApi(applicationContext, (application as ScenixApp).prefs) }
    private var prefs: JSONObject? = null
    private var rowList: JSONArray? = null
    private var globalRows: JSONObject? = null
    private var rowsCfg: JSONObject? = null
    private var picker: View? = null
    /** The row that had the remote before a picker opened; focus returns there on close. */
    private var pickerReturn: View? = null
    // The D-pad is trapped inside a picker: Android's default search treats the overlay as a plain
    // rectangle and can hand focus to a row behind it, which turned "pick a theme" into "open Live TV".
    private var pickerRows: List<View> = emptyList()
    private var lockInfo: JSONObject? = null
    private var profileKind = ""
    private var pinLayer: FrameLayout? = null
    private var pinIsGate = false
    private val pinBuf = StringBuilder()
    private var pinSub: TextView? = null
    private var pinDots: TextView? = null
    private var pinOnDone: ((String) -> Unit)? = null
    private var sessionPin = ""
    private var adminUnlocked = false



    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        app = (application as ScenixApp).ctx
        profileId = intent?.getStringExtra(EXTRA_PROFILE).orEmpty()
        if (profileId.isBlank()) { finish(); return }

        root = FrameLayout(this).apply { setBackgroundColor(app.theme.bg) }
        page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(app.theme.bg)
            visibility = View.INVISIBLE
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
        root.addView(page, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
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
        val title = Views.text(app, "li", 26f, app.theme.ink, "Settings", bold = true)
        title.setPadding(app.theme.px(20f), 0, 0, 0)
        bar.addView(title)
        bar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        status = Views.text(app, "meta", 19f, app.theme.ink2, "", maxLines = 1)
        bar.addView(status)
        return bar
    }

    private fun load() {
        status.text = "Reading your preferences…"
        api.prefs(profileId) { p, error ->
            if (isFinishing || isDestroyed) return@prefs
            if (p == null) {
                status.text = error ?: "The hub is not answering"
                return@prefs
            }
            prefs = p
            status.text = ""
            api.profiles { list, _ ->
                if (list != null) {
                    profileKind = if (list.firstOrNull { it.id == profileId }?.kids == true) "kids" else "adult"
                }
                if (!isFinishing && !isDestroyed) loadRows()
            }
        }
    }

    /** Settings carries the row metadata and its hidden/order config; the editor is optional. */
    private fun loadRows() {
        api.settings { root, _ ->
            if (isFinishing || isDestroyed) return@settings
            if (root != null) {
                rowList = root.optJSONArray("rows")
                globalRows = root.optJSONObject("settings")?.optJSONObject("rows")
                lockInfo = root.optJSONObject("settings")?.optJSONObject("lock")
                // The editor edits this profile's rows; the household's list is the floor it cannot lift.
                rowsCfg = prefs?.optJSONObject("rows") ?: JSONObject()
            }
            paint()
            page.visibility = View.VISIBLE
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
        val p = prefs ?: return
        addRow("Audio language", languageName(firstOf(p, "audioLanguages")), "The hub mixes this language into copies that lack it") {
            showLanguagePicker("Audio language", "audioLanguages")
        }
        addRow("Subtitles", languageName(firstOf(p, "subtitleLanguages")), "Preferred subtitle language") {
            showLanguagePicker("Subtitles", "subtitleLanguages")
        }
        addRow("Quality ceiling", qualityLabel(p.optString("qualityCap", "auto")), "Largest copy this TV should be offered") {
            showQualityPicker()
        }
        val shelfRows = rowList
        if (shelfRows != null && shelfRows.length() > 0) {
            val hidden = effectiveHidden()
            val visible = rowIds().filter { profileKind != "kids" || rowScope(it) != "adult" }
            addRow("Home rows", "${visible.count { it !in hidden }} of ${visible.size} shown",
                "This profile's shelves — hide, show or reorder") { showHomeRows() }
        }
        addRow("Live TV channels", "This profile's lineup", "Choose the channels and their zap order") {
            startActivity(
                Intent(this, LiveChannelsActivity::class.java)
                    .putExtra(LiveChannelsActivity.EXTRA_PROFILE, profileId)
            )
        }
        addRow("Controllers", controllersSummary(), "See what is connected, test its buttons, pair a new pad") {
            startActivity(Intent(this, ControllersActivity::class.java))
        }
        addRow("Profiles", "Switch person", "The native who's-watching gate") {
            startActivity(Intent(this, ProfilesActivity::class.java))
        }
        // Auto-update (core/Updates.kt): the builds live as GitHub Releases on the project repo.
        // The check is the app's; the install is always the person's — the dialog offers, Android
        // confirms, nothing is ever swapped under a viewer mid-frame.
        addRow("Auto-update", if (app.prefs.autoUpdate()) "On" else "Off",
            "Look for a new Scenix build when the app starts — you always confirm the install") {
            app.prefs.setAutoUpdate(!app.prefs.autoUpdate())
            paint()
        }
        addRow("Check for updates",
            "v${Updates.currentVersionName(this)} (${Updates.currentVersionCode(this)})",
            "The newest build published on the Scenix GitHub releases") {
            Updates.check(this) { rel, error ->
                if (isFinishing || isDestroyed) return@check
                runOnUiThread {
                    when {
                        rel != null -> Updates.offer(this, rel)
                        error != null -> android.widget.Toast.makeText(
                            this, "Update check failed: $error", android.widget.Toast.LENGTH_LONG,
                        ).show()
                        else -> android.widget.Toast.makeText(
                            this, "This is the newest build", android.widget.Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
            }
        }
        // The admin areas are a separate door from the profile PINs. With no admin lock set, a kids
        // profile does not see them at all (the default-safe state). Once the household turns the lock
        // on, the rows exist for everyone and the admin PIN is what opens them — a person who knows it
        // can use them from any profile, exactly like an office's shared settings PIN.
        val adminOn = lockInfo?.optBoolean("enabled") == true
        // A kids profile never sees the admin rows, lock or no lock. An authorised person uses an
        // adult profile and proves the admin PIN there.
        if (profileKind != "kids") {
            // The backend is a property of this television, not of a person: a new hub address, a
            // different box on the LAN, or standalone. It lives behind the same admin door as the
            // other household settings, and a kids profile never sees it at all.
            val backend = app.prefs.backendChoice()
            val hubUrl = app.prefs.hubUrl(getString(R.string.hub_url))
            val hubName = app.prefs.discoveredHubs().firstOrNull { it.url == hubUrl }?.name.orEmpty()
            val backendValue = when {
                backend == Prefs.BACKEND_STANDALONE -> "Standalone"
                hubName.isNotBlank() -> "Hub · $hubName"
                else -> "Hub · $hubUrl"
            }
            addRow("Hub connection", backendValue,
                "Change hub, enter an address, or use this TV on its own") {
                requireAdmin {
                    startActivity(
                        Intent(this, MainActivity::class.java).putExtra("setup", true)
                    )
                }
            }
            val picked = Theme.catalog(assets).firstOrNull { it.id == app.prefs.theme() }
            val themeName = when {
                picked == null -> app.prefs.theme()
                picked.pack.isBlank() -> picked.name
                else -> picked.name + " · " + picked.pack
            }
            addRow("Theme", themeName, "The look of the native screens and the web UI") {
                requireAdmin { pickTheme() }
            }
            // Accessibility, not taste: one multiplier over the whole theme so text, tiles, gaps and
            // the hero all grow together for far seats and weak eyes. No admin door on this one.
            val zoomNames = listOf("Default", "Large", "Largest")
            val zoomSteps = listOf(1f, 1.15f, 1.3f)
            val z = app.prefs.uiZoom()
            val zoomNow = if (z < 1.05f) 0 else if (z < 1.25f) 1 else 2
            addRow("Text & tile size", zoomNames[zoomNow], "Everything on screen grows together — for far seats and weak eyes") {
                app.prefs.setUiZoom(zoomSteps[(zoomNow + 1) % 3])
                (application as ScenixApp).reloadTheme()
                // The task rebuilds so every screen lays out at the new size, the way a real TV
                // relaunches when its zoom changes.
                startActivity(
                    Intent(this, ProfilesActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                )
            }
            addRow("System", "Name, languages, limits", "The hub's own name, the languages its addons search, and how many copies it ranks") {
                requireAdmin { startActivity(Intent(this, SystemActivity::class.java)) }
            }
            addRow("Live TV", "Playlist and groups", "The household playlist address and the genres every profile skips") {
                requireAdmin { startActivity(Intent(this, LiveSettingsActivity::class.java)) }
            }
            // Only standalone has files on this machine. Against a hub, this TV has no folder of its
            // own and the row would be a lie, so it is absent rather than empty.
            if (app.prefs.backendChoice() == Prefs.BACKEND_STANDALONE) {
                val local = com.scenix.tv.core.LocalHub.get(this).localStatus()
                val files = local.optInt("files", 0)
                val folder = local.optString("name", "").ifBlank { "no folder chosen" }
                val value = when {
                    !local.optBoolean("granted", false) -> "Choose a folder"
                    files > 0 -> "$files files · $folder"
                    else -> folder
                }
                addRow("Local files", value,
                    "Play video and audio from a folder on this TV — no network and no addon") {
                    requireAdmin { pickLocalFolder() }
                }
            }
            addRow("Categories", "Shelves built from your catalogues", "Name a catalogue or genre this hub carries as its own Home shelf") {
                requireAdmin { startActivity(Intent(this, CategoriesActivity::class.java)) }
            }
            addRow("Manage profiles", "Add, rename, PINs", "Create people, set their kind and remove them") {
                requireAdmin { startActivity(Intent(this, ManageProfilesActivity::class.java)) }
            }
            addRow("Source packs", "Audio donors and picture packs", "The audio the hub mixes into a copy missing your language") {
                requireAdmin { startActivity(Intent(this, SourcePacksActivity::class.java)) }
            }
            addRow("Add-ons", "Sources the hub reads", "Turn one off, remove it, or add a manifest URL") {
                requireAdmin { startActivity(Intent(this, AddonsActivity::class.java)) }
            }
            addRow("Debrid", "Cached copies", "Provider, token and the on/off switch") {
                requireAdmin { startActivity(Intent(this, DebridActivity::class.java)) }
            }
            if (profileKind != "kids") {
                addRow("Settings lock", if (adminOn) "On" else "Off",
                    "A separate admin PIN for add-ons, packs and these common settings") {
                    changeLock()
                }
                if (adminOn) {
                    addRow("Change admin PIN", "Choose a new common-settings PIN", "The current PIN is needed") {
                        changePin()
                    }
                }
            }
        }
    }

    /** What the Controllers row says about the TV right now — honest, not "Connected?" guessing. */
    private fun controllersSummary(): String {
        val gamepad = android.view.InputDevice.SOURCE_GAMEPAD
        val pads = try {
            val im = getSystemService(android.content.Context.INPUT_SERVICE) as android.hardware.input.InputManager
            android.view.InputDevice.getDeviceIds().toList().mapNotNull { id ->
                try { im.getInputDevice(id) } catch (t: Throwable) { null }
            }.filter { d -> (d.sources and gamepad) == gamepad && !d.name.contains("virtual", true) }
        } catch (t: Throwable) { emptyList() }
        return when (pads.size) {
            0 -> "None connected"
            1 -> "1 connected · " + pads[0].name.take(24)
            else -> pads.size.toString() + " connected"
        }
    }

    /**
     * The system folder picker. A tree URI is the only portable way for this app to read a folder on a
     * modern Android TV: it needs no broad storage permission, and the grant survives a reboot once it
     * is persisted. The URI is stored in this backend's settings, so the same pick works after the
     * process is gone.
     */
    private fun pickLocalFolder() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        try {
            startActivityForResult(intent, REQ_LOCAL_TREE)
        } catch (t: Throwable) {
            // Many Android TV images ship without DocumentsUI. The platform's own media index is then
            // the only folder this app can read, and one runtime permission is enough to use it.
            status.text = "No folder picker on this TV"
            pickLocalFromStore()
        }
    }

    private fun pickLocalFromStore() {
        val hub = com.scenix.tv.core.LocalHub.get(this)
        if (!hub.localPermission()) {
            requestPermissions(hub.localPermissionNames(), REQ_LOCAL_PERMISSION)
            return
        }
        saveLocalTree(com.scenix.tv.core.LocalLibrary.MEDIASTORE, "This TV's videos")
    }

    private fun saveLocalTree(tree: String, label: String) {
        status.text = "Indexing…"
        api.saveSettings(
            JSONObject().put("settings", JSONObject().put("local",
                JSONObject().put("tree", tree).put("name", label))),
        ) { _, error ->
            if (isFinishing || isDestroyed) return@saveSettings
            if (error != null) {
                status.text = error
                return@saveSettings
            }
            status.text = "Library saved"
            paint()
            // The index is built behind the save; repaint once so the row can say how many files it
            // found instead of leaving "This TV's videos" forever.
            rows.postDelayed({ if (!isFinishing && !isDestroyed) paint() }, 1800)
        }
    }

    @Deprecated("Activity result API")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_LOCAL_PERMISSION) return
        if (grantResults.isNotEmpty() && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            saveLocalTree(com.scenix.tv.core.LocalLibrary.MEDIASTORE, "This TV's videos")
        } else {
            status.text = "Without this permission this TV cannot read its own media"
        }
    }

    @Deprecated("Activity result API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_LOCAL_TREE || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (t: Throwable) {
            status.text = "The folder grant could not be kept"
            return
        }
        val label = uri.lastPathSegment?.substringAfterLast(':')?.ifBlank { null } ?: "Local files"
        saveLocalTree(uri.toString(), label)
    }

    private fun addRow(label: String, value: String, note: String, action: () -> Unit) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(app.theme.px(24f), 0, app.theme.px(24f), 0)
            background = Views.rounded(app.theme.surface, app.theme.px(10f))
            isFocusable = true
            isClickable = true
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
        val v = Views.text(app, "meta", 20f, app.theme.ink2, value, maxLines = 1, gravity = Gravity.END)
        row.addView(v)
        Focus.attach(row, row, app, app.theme.px(10f), scale = false, elevate = false)
        rows.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, app.theme.px(88f)).apply {
            bottomMargin = app.theme.px(10f)
        })
    }

    // ---- Home rows -------------------------------------------------------------------------

    private fun rowIds(): List<String> {
        val list = rowList ?: return emptyList()
        val out = ArrayList<String>()
        for (i in 0 until list.length()) {
            val id = list.optJSONObject(i)?.optString("id").orEmpty()
            if (id.isNotBlank()) out.add(id)
        }
        return out
    }

    private fun rowScope(id: String): String {
        val list = rowList ?: return "all"
        for (i in 0 until list.length()) {
            val o = list.optJSONObject(i) ?: continue
            if (o.optString("id") == id) return o.optString("scope", "all")
        }
        return "all"
    }

    private fun rowTitle(id: String): String {
        val list = rowList ?: return id
        for (i in 0 until list.length()) {
            val o = list.optJSONObject(i) ?: continue
            if (o.optString("id") == id) return o.optString("title", id).ifBlank { id }
        }
        return id
    }

    private fun rowOrder(): MutableList<String> {
        val ids = rowIds()
        val named = ArrayList<String>()
        rowsCfg?.optJSONArray("order")?.let { arr ->
            for (i in 0 until arr.length()) {
                val v = arr.optString(i, "")
                if (v in ids && v !in named) named.add(v)
            }
        }
        for (id in ids) if (id !in named) named.add(id)
        return named
    }

    private fun localHidden(): MutableList<String> {
        val out = ArrayList<String>()
        rowsCfg?.optJSONArray("hidden")?.let { arr ->
            for (i in 0 until arr.length()) {
                val v = arr.optString(i, "")
                if (v.isNotBlank() && v !in out) out.add(v)
            }
        }
        return out
    }

    private fun globalHidden(): Set<String> {
        val out = HashSet<String>()
        globalRows?.optJSONArray("hidden")?.let { arr ->
            for (i in 0 until arr.length()) {
                val v = arr.optString(i, "")
                if (v.isNotBlank()) out.add(v)
            }
        }
        return out
    }

    private fun effectiveHidden(): Set<String> = globalHidden() + localHidden()

    private fun showHomeRows() {
        val order = rowOrder()
        if (order.isEmpty()) {
            status.text = "The hub listed no Home rows"
            return
        }
        val hidden = effectiveHidden()
        val options = ArrayList<Pair<String, String>>()
        for (id in order) {
            if (profileKind == "kids" && rowScope(id) == "adult") continue
            options.add(id to rowTitle(id) + if (id in hidden) "  ·  Hidden" else "  ·  On Home")
        }
        showPicker("Home rows", options, "") { id -> showRowActions(id) }
    }

    private fun showRowActions(id: String) {
        val order = rowOrder()
        val at = order.indexOf(id)
        if (at < 0) return
        val title = rowTitle(id)
        val hidden = id in localHidden()
        val householdHidden = id in globalHidden()
        val options = ArrayList<Pair<String, String>>()
        if (!householdHidden) {
            options.add("toggle" to (if (hidden) "Show \"$title\" on Home" else "Hide \"$title\" from Home"))
        }
        if (at > 0) options.add("earlier" to "Move \"$title\" earlier")
        if (at < order.size - 1) options.add("later" to "Move \"$title\" later")
        options.add("back" to "Back to rows")
        showPicker(title, options, "") { action ->
            when (action) {
                "back" -> showHomeRows()
                "toggle" -> {
                    val nextHidden = localHidden()
                    if (hidden) nextHidden.remove(id) else if (id !in nextHidden) nextHidden.add(id)
                    writeRows(nextHidden, order)
                }
                "earlier", "later" -> {
                    val next = order.toMutableList()
                    next.removeAt(at)
                    next.add(if (action == "earlier") at - 1 else at + 1, id)
                    writeRows(localHidden(), next)
                }
            }
        }
    }

    private fun writeRows(hidden: List<String>, order: List<String>) {
        closePicker()
        status.text = "Saving…"
        val cfg = JSONObject()
            .put("hidden", JSONArray(hidden))
            .put("order", JSONArray(order))
        api.savePrefs(profileId, JSONObject().put("rows", cfg)) { saved, error ->
            if (isFinishing || isDestroyed) return@savePrefs
            if (error != null) {
                status.text = error
                return@savePrefs
            }
            if (saved != null) {
                prefs = saved
                rowsCfg = saved.optJSONObject("rows") ?: JSONObject()
            }
            status.text = "Saved"
            paint()
            showHomeRows()
            rows.postDelayed({ status.text = "" }, 2500)
        }
    }

    private fun firstOf(p: JSONObject, key: String): String {
        val a = p.optJSONArray(key) ?: return ""
        for (i in 0 until a.length()) {
            val v = a.optString(i, "")
            if (v.isNotBlank() && v != "null") return v
        }
        return ""
    }

    private fun languageName(code: String): String =
        if (code.isBlank()) "Not set" else LANGUAGES.firstOrNull { it.first == code }?.second ?: code.uppercase()

    private fun qualityLabel(v: String): String = if (v.isBlank() || v == "auto") "Auto" else v.uppercase()

    private fun showLanguagePicker(title: String, key: String) {
        val current = firstOf(prefs ?: return, key)
        val options = ArrayList<Pair<String, String>>()
        for (l in LANGUAGES) options.add(l.first to l.second)
        if (current.isNotBlank() && options.none { it.first == current }) options.add(0, current to current.uppercase())
        showPicker(title, options, current) { code ->
            val old = prefs?.optJSONArray(key)
            val list = JSONArray()
            list.put(code)
            if (old != null) {
                for (i in 0 until old.length()) {
                    val v = old.optString(i, "")
                    if (v.isNotBlank() && v != code) list.put(v)
                }
            }
            write(JSONObject().put(key, list))
        }
    }

    private fun showQualityPicker() {
        val p = prefs ?: return
        val levels = p.optJSONArray("qualityLevels")
        val options = ArrayList<Pair<String, String>>()
        if (levels != null) {
            for (i in 0 until levels.length()) {
                val v = levels.optString(i, "")
                if (v.isNotBlank()) options.add(v to qualityLabel(v))
            }
        }
        if (options.isEmpty()) {
            for (v in listOf("auto", "4k", "1080p", "720p", "480p")) options.add(v to qualityLabel(v))
        }
        showPicker("Quality ceiling", options, p.optString("qualityCap", "auto")) { v ->
            write(JSONObject().put("qualityCap", v))
        }
    }

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
            background = Views.rounded(app.theme.color("panelScrim", "#E6000000"), app.theme.px(12f),
                app.theme.px(1f), app.theme.line)
            elevation = app.theme.px(16f).toFloat()
        }
        panel.addView(Views.text(app, "li", 26f, app.theme.ink, title, bold = true),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = app.theme.px(12f)
            })
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val firstRows = ArrayList<View>()
        for ((value, label) in options) {
            val selected = value == current
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
            row.addView(Views.text(app, "li", 22f,
                if (selected) app.theme.color("panelValue", "#F2C33A") else app.theme.ink,
                "$tick  $label", bold = selected, maxLines = 1),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            Focus.attach(row, row, app, app.theme.px(8f), scale = false, elevate = false)
            list.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, app.theme.px(60f)).apply {
                topMargin = app.theme.px(4f)
            })
            firstRows.add(row)
        }
        // The language list is longer than any panel; a scrolling list keeps every option reachable
        // while the rows inside still take the D-pad ring and the scroll view itself never does.
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
        pickerRows = firstRows
        val target = firstRows.getOrNull(options.indexOfFirst { it.first == current }.coerceAtLeast(0)) ?: firstRows.firstOrNull()
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
        pickerRows = emptyList()
    }

    /** Move the ring one option up/down in the open picker, wrapping at the ends. */
    private fun movePicker(delta: Int): Boolean {
        if (pickerRows.isEmpty()) return false
        val at = pickerRows.indexOfFirst { it.isFocused }
        val next = if (at < 0) 0 else (at + delta).coerceIn(0, pickerRows.lastIndex)
        if (next == at) return true
        pickerRows[next].requestFocus()
        return true
    }

    private fun write(patch: JSONObject) {
        status.text = "Saving…"
        api.savePrefs(profileId, patch) { p, error ->
            if (isFinishing || isDestroyed) return@savePrefs
            if (p == null) {
                status.text = error ?: "The hub did not save that"
                return@savePrefs
            }
            prefs = p
            status.text = "Saved"
            paint()
            rows.postDelayed({ status.text = "" }, 2500)
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (pinLayer != null) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_BACK -> {
                        if (pinIsGate) finish() else closePinPad()
                        return true
                    }
                    KeyEvent.KEYCODE_DEL -> { backspacePin(); return true }
                    KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> { submitPin(); return true }
                    in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> {
                        addPinDigit(event.keyCode - KeyEvent.KEYCODE_0)
                        return true
                    }
                }
            }
            return super.dispatchKeyEvent(event)
        }
        if (picker != null && event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_BACK -> {
                    closePicker()
                    // The row that opened the picker is the last focused view; Android restores it.
                    return true
                }
                KeyEvent.KEYCODE_DPAD_UP -> return movePicker(-1)
                KeyEvent.KEYCODE_DPAD_DOWN -> return movePicker(1)
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    val focused = pickerRows.firstOrNull { it.isFocused }
                    if (focused != null) {
                        focused.performClick()
                        return true
                    }
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    // ---- settings lock -----------------------------------------------------------------------

    /**
     * The admin areas share one PIN, separate from any profile's PIN. It is asked only when a protected
     * area is opened, not when Settings itself is opened: changing this profile's audio language is not
     * a household act. Once proved in this visit, the other admin rows open without asking again.
     */
    private fun requireAdmin(block: () -> Unit) {
        if (lockInfo?.optBoolean("enabled") != true || adminUnlocked) {
            block()
            return
        }
        showPinPad("Admin PIN required", "Enter the household settings PIN", gate = false) { entered ->
            api.unlockSettings(entered) { ok, error ->
                if (isFinishing || isDestroyed) return@unlockSettings
                if (ok) {
                    adminUnlocked = true
                    closePinPad()
                    block()
                } else {
                    pinError(error ?: "That PIN is not right")
                }
            }
        }
    }

    private fun changePin() {
        showPinPad("Change the settings PIN", "Enter the current PIN", gate = false) { current ->
            api.unlockSettings(current) { ok, _ ->
                if (isFinishing || isDestroyed) return@unlockSettings
                if (!ok) { pinError("That PIN is not right"); return@unlockSettings }
                showPinPad("Change the settings PIN", "Choose 4 to 8 digits", gate = false) { fresh ->
                    if (fresh.length !in 4..8) { pinError("Use 4 to 8 digits"); return@showPinPad }
                    api.saveSettingsLock(true, fresh, current) { root, error ->
                        if (isFinishing || isDestroyed) return@saveSettingsLock
                        if (error != null) { pinError(error); return@saveSettingsLock }
                        lockInfo = root?.optJSONObject("lock") ?: JSONObject().put("enabled", true)
                        sessionPin = fresh
                        closePinPad()
                        paint()
                    }
                }
            }
        }
    }

    private fun pickTheme() {
        val choices = Theme.catalog(assets)
        showPicker("Theme", choices.map {
            it.id to if (it.pack.isBlank()) it.name else it.name + " · " + it.pack
        }, app.prefs.theme()) { id ->
            app.prefs.setTheme(id)
            (application as ScenixApp).reloadTheme()
            // Keep the browser chrome on the same theme as the television.
            api.saveSettings(JSONObject().put("settings", JSONObject().put("theme", id))) { _, _ -> }
            // The whole task was built with the old tokens; start clean at the profile gate so every
            // native screen is drawn with the new theme.
            startActivity(
                Intent(this, ProfilesActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            )
        }
    }

    private fun changeLock() {
        val on = lockInfo?.optBoolean("enabled") == true
        if (on) {
            showPinPad("Turn off the settings lock", "Enter the current PIN", gate = false) { entered ->
                api.saveSettingsLock(false, null, entered) { root, error ->
                    if (isFinishing || isDestroyed) return@saveSettingsLock
                    if (error != null) { pinError(error); return@saveSettingsLock }
                    lockInfo = root?.optJSONObject("lock") ?: JSONObject().put("enabled", false)
                    sessionPin = ""
                    closePinPad()
                    paint()
                }
            }
        } else if (lockInfo?.optBoolean("pinSet") == true) {
            // The lock is off but a PIN is already stored (it was turned off, not erased). Re-enabling
            // must prove the stored PIN; the hub refuses a blind change.
            showPinPad("Turn on the settings lock", "Enter the current PIN", gate = false) { entered ->
                api.saveSettingsLock(true, null, entered) { root, error ->
                    if (isFinishing || isDestroyed) return@saveSettingsLock
                    if (error != null) { pinError(error); return@saveSettingsLock }
                    lockInfo = root?.optJSONObject("lock") ?: JSONObject().put("enabled", true)
                    adminUnlocked = true
                    sessionPin = entered
                    closePinPad()
                    paint()
                }
            }
        } else {
            showPinPad("Set a settings PIN", "Choose 4 to 8 digits", gate = false) { entered ->
                if (entered.length !in 4..8) { pinError("Use 4 to 8 digits"); return@showPinPad }
                api.saveSettingsLock(true, entered, null) { root, error ->
                    if (isFinishing || isDestroyed) return@saveSettingsLock
                    if (error != null) { pinError(error); return@saveSettingsLock }
                    lockInfo = root?.optJSONObject("lock") ?: JSONObject().put("enabled", true)
                    adminUnlocked = true
                    sessionPin = entered
                    closePinPad()
                    paint()
                }
            }
        }
    }

    private fun showPinPad(title: String, subtitle: String, gate: Boolean, onDone: (String) -> Unit) {
        closePinPad()
        pinIsGate = gate
        pinBuf.setLength(0)
        pinOnDone = onDone
        val layer = FrameLayout(this).apply { setBackgroundColor(Color.rgb(16, 16, 16)) }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(app.theme.px(48f), app.theme.px(36f), app.theme.px(48f), app.theme.px(24f))
        }
        panel.addView(Views.text(app, "li", 34f, app.theme.ink, title, bold = true, gravity = Gravity.CENTER))
        pinSub = Views.text(app, "meta", 19f, app.theme.ink3, subtitle, gravity = Gravity.CENTER, maxLines = 2)
        panel.addView(pinSub, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = app.theme.px(8f)
        })
        pinDots = Views.text(app, "li", 32f, app.theme.ink, "○ ○ ○ ○", bold = true, gravity = Gravity.CENTER)
        panel.addView(pinDots, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, app.theme.px(64f)).apply {
            topMargin = app.theme.px(16f)
        })
        val keys = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val rows = listOf(
            listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"),
            listOf("CLR", "0", "OK"),
        )
        for (row in rows) {
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (label in row) {
                val b = Views.text(app, "li", 24f, app.theme.ink, label, bold = true, gravity = Gravity.CENTER)
                b.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                    app.theme.px(if (label.length > 1) 17f else 26f).toFloat())
                b.background = Views.rounded(app.theme.surface2, app.theme.px(10f))
                b.isFocusable = true
                b.isClickable = true
                b.setOnClickListener {
                    when (label) {
                        "CLR" -> { pinBuf.setLength(0); renderPin() }
                        "OK" -> submitPin()
                        else -> addPinDigit(label.toInt())
                    }
                }
                Focus.attach(b, b, app, app.theme.px(10f), scale = false, elevate = false)
                line.addView(b, LinearLayout.LayoutParams(app.theme.px(120f), app.theme.px(64f)).apply {
                    marginStart = app.theme.px(6f)
                    marginEnd = app.theme.px(6f)
                })
            }
            keys.addView(line, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, app.theme.px(70f)))
        }
        panel.addView(keys, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        layer.addView(panel, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.CENTER })
        root.addView(layer, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        pinLayer = layer
        // The keypad is a full-screen overlay; the page behind it must not take the ring.
        page.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        (keys.getChildAt(0) as? ViewGroup)?.getChildAt(0)?.post {
            (keys.getChildAt(0) as? ViewGroup)?.getChildAt(0)?.requestFocus()
        }
        renderPin()
    }

    private fun addPinDigit(d: Int) {
        if (pinBuf.length < 8) { pinBuf.append(d); renderPin() }
    }

    private fun backspacePin() {
        if (pinBuf.isNotEmpty()) { pinBuf.deleteCharAt(pinBuf.length - 1); renderPin() }
    }

    private fun renderPin() {
        val n = pinBuf.length.coerceAtLeast(4)
        pinDots?.text = (0 until n).joinToString(" ") { if (it < pinBuf.length) "●" else "○" }
    }

    private fun submitPin() {
        val entered = pinBuf.toString()
        if (entered.isBlank()) return
        pinOnDone?.invoke(entered)
    }

    private fun pinError(message: String) {
        pinBuf.setLength(0)
        renderPin()
        pinSub?.text = message
        pinSub?.setTextColor(app.theme.color("warn", "#F2C33A"))
    }

    private fun closePinPad() {
        pinLayer?.let { root.removeView(it) }
        pinLayer = null
        pinOnDone = null
        page.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
    }

    companion object {
        const val EXTRA_PROFILE = "profile"
        private const val REQ_LOCAL_TREE = 9001
        private const val REQ_LOCAL_PERMISSION = 9002
        private val LANGUAGES = listOf(
            "hin" to "Hindi", "eng" to "English", "pan" to "Punjabi", "tam" to "Tamil",
            "tel" to "Telugu", "ben" to "Bengali", "mar" to "Marathi", "mal" to "Malayalam",
            "kan" to "Kannada", "guj" to "Gujarati", "urd" to "Urdu", "spa" to "Spanish",
            "fre" to "French", "ger" to "German", "jpn" to "Japanese", "kor" to "Korean",
        )
    }
}
