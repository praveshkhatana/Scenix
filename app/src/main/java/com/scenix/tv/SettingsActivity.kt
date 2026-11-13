package com.scenix.tv

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import com.scenix.tv.core.Motion
import com.scenix.tv.core.Focus
import com.scenix.tv.core.HubEndpoint
import com.scenix.tv.core.HubUpdate
import com.scenix.tv.core.Prefs
import com.scenix.tv.core.PhonePairing
import com.scenix.tv.core.PhoneRemote
import com.scenix.tv.core.Screens
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
class SettingsActivity : Activity(), PhoneRemote.TextTarget {

    private lateinit var app: AppContext
    private lateinit var profileId: String
    private val remote get() = (application as ScenixApp).phoneRemote
    private lateinit var root: FrameLayout
    private lateinit var page: LinearLayout
    private lateinit var rows: LinearLayout
    private lateinit var contentScroll: ScrollView
    private lateinit var status: TextView
    private lateinit var phoneRail: LinearLayout
    private val api by lazy { BrowseApi(applicationContext, (application as ScenixApp).prefs) }
    private var prefs: JSONObject? = null
    private var rowList: JSONArray? = null
    private var globalRows: JSONObject? = null
    private var rowsCfg: JSONObject? = null
    private var picker: View? = null
    // The row that opened the last picker; it takes focus back when the picker
    // closes, so the remote never lands on the page's back control by accident.
    private var pickerReturn: View? = null
    // The D-pad is trapped inside a picker: Android's default search treats the overlay as a plain
    // rectangle and can hand focus to a row behind it, which turned "pick a theme" into "open Live TV".
    private var pickerRows: List<View> = emptyList()
    private var lockInfo: JSONObject? = null
    // A failed settings read is not evidence that the household lock is off. Protected routes stay
    // absent until this screen receives a current settings object from the Hub.
    private var householdSettingsReady = false
    // Settings has household-level routes, so a profile lookup must fail closed.  A profile is
    // allowed to see them only after this screen has positively identified it as an adult.
    private var profileKind = "unknown"
    // The name shown on the Overview summary. Kept beside `profileKind` because both come from the
    // same trusted profile snapshot and both must clear when that snapshot fails.
    private var profileName = ""
    private var pinLayer: FrameLayout? = null
    private var pinIsGate = false
    private val pinBuf = StringBuilder()
    private var pinSub: TextView? = null
    private var pinDots: TextView? = null
    private var pinOnDone: ((String) -> Unit)? = null
    private var adminUnlocked = false
    // New hubs bind an unlock to a short-lived server capability. Older compatible hubs only
    // answer `{ok:true}`, so retain their visit-local behavior while requiring a fresh PIN after a
    // real capability expires or the server rejects it.
    private var adminUnlockUsesSession = false

    /**
     * The page is one scannable column of clearly labelled groups. The remote never has to learn a
     * second navigation level: safe preferences are in plain sight, and the household areas sit
     * behind one locked row at the bottom. The phone QR stays pinned beside the column for the whole
     * visit, so pairing is never a hunt through a sub-page.
     */
    private val controlsByKey = LinkedHashMap<String, View>()
    // The first onResume follows onCreate and the initial load below. Later resumes are returns from
    // child administration screens, where another device may have changed or expired the Settings
    // capability while this Activity was paused.
    private var resumedOnce = false
    /**
     * A return from a child screen refreshes the server snapshot, but it must not tear down the
     * control centre while the three reads are in flight.  Apart from looking like a flicker, the
     * old behaviour reset a viewer from (say) Household back to Overview every time an editor returned.
     */
    private var settingsRefreshVersion = 0
    private var householdRefreshPending = false
    private var deferredFocusKey: String? = null
    private var deferredScrollY: Int? = null

    // ---- phone pairing rail --------------------------------------------------------------------
    // The session itself belongs to the app (core/PhoneRemote) so a phone that paired here keeps
    // driving Search, Detail and the player after this screen closes. This Activity only paints the
    // rail from the snapshots the relay publishes.
    private var remoteStatusView: TextView? = null
    private var remoteUrlView: TextView? = null
    private var remoteCodeView: TextView? = null
    private var remoteQrHost: FrameLayout? = null
    private var remotePairing: PhoneRemote.Pairing? = null



    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        app = (application as ScenixApp).ctx
        profileId = intent?.getStringExtra(EXTRA_PROFILE).orEmpty()
        if (profileId.isBlank()) { finish(); return }

        root = FrameLayout(this).apply {
            val ground = Screens.wall(app)
            if (ground != null) background = ground else setBackgroundColor(app.theme.bg)
        }
        page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.INVISIBLE
        }
        val bar = topBar()
        page.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, app.theme.px(96f)))
        // The settings column and the phone rail are siblings. The rail never scrolls away, so the
        // QR is already on screen when Settings opens, and the remote can still walk the whole
        // column with UP/DOWN without ever being asked to find the pairing card.
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
            clipToPadding = false
        }
        contentScroll = ScrollView(this).apply {
            isFocusable = false
            isFocusableInTouchMode = false
            isVerticalScrollBarEnabled = false
            clipToPadding = false
            clipChildren = false
        }
        rows = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // Extra canvas keeps focused plates and keylines inside the 1080p scroll viewport.
            setPadding(app.theme.gutter, app.theme.px(10f), app.theme.px(10f), app.theme.px(72f))
            clipChildren = false
            clipToPadding = false
        }
        contentScroll.addView(rows)
        body.addView(contentScroll, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        phoneRail = buildPhoneRail()
        body.addView(phoneRail, LinearLayout.LayoutParams(
            maxOf(app.theme.px(384f), 320), ViewGroup.LayoutParams.MATCH_PARENT,
        ))
        page.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(page, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)
        Motion.enter(app.theme, root)
        load()
    }

    override fun onResume() {
        super.onResume()
        if (resumedOnce && !isFinishing && !isDestroyed) load()
        resumedOnce = true
        // The rail paints from the relay's snapshots. The session is not this screen's to keep or
        // close — it belongs to the app, and it survives leaving Settings.
        remote.onPairing = { pairing ->
            remotePairing = pairing
            renderPhoneRail()
        }
        remote.ensureSession()
    }

    override fun onPause() {
        remote.onPairing = null
        super.onPause()
    }

    private fun topBar(): View {
        val (bar, s) = Screens.topBar(this, app, "Settings") { onBackPressed() }
        status = s
        return bar
    }

    override fun onBackPressed() = super.onBackPressed()

    private fun load() {
        val generation = ++settingsRefreshVersion
        val wasVisible = page.visibility == View.VISIBLE
        // Preserve the exact remote stop and viewport before an ordinary child return refresh. The
        // eventual paint consumes these values once, so a return from an editor keeps its place in
        // the one long page.
        if (wasVisible) {
            deferredFocusKey = focusedControlKey()
            deferredScrollY = contentScroll.scrollY
        }
        // A refresh replaces the household authority snapshot as a whole.  Keep the existing
        // composition visible while its requests are in flight, but never retain its lock state as
        // an authority: protected actions already wait on `householdRefreshPending`, and the new
        // snapshot is the only one allowed to re-enable household routes.
        lockInfo = null
        householdSettingsReady = false
        householdRefreshPending = true
        status.text = if (wasVisible) "Refreshing settings…" else "Reading your preferences…"

        // These endpoints are independent. Starting them together removes two serial network
        // waits on every return from a child screen, and applying one coherent snapshot prevents
        // the section rail from briefly claiming adult access before its profile check arrives.
        var prefsDone = false
        var profilesDone = false
        var settingsDone = false
        var nextPrefs: JSONObject? = null
        var nextPrefsError: String? = null
        var nextProfiles: List<ProfileInfo>? = null
        var nextProfilesError: String? = null
        var nextSettings: JSONObject? = null
        var nextSettingsError: String? = null

        fun completeIfReady() {
            if (!prefsDone || !profilesDone || !settingsDone || generation != settingsRefreshVersion ||
                isFinishing || isDestroyed
            ) return

            householdRefreshPending = false
            val resolvedPrefs = nextPrefs
            if (resolvedPrefs == null) {
                // Do not erase a currently visible, known-good control centre just because a
                // refresh lost the network. Protected routes still fail closed: a failed refresh
                // cannot keep an old adult/household assertion alive. On first entry there is no
                // snapshot to retain, so reveal the existing status line instead of a blank wall.
                profileKind = "unknown"
                profileName = ""
                householdSettingsReady = false
                lockInfo = null
                status.text = nextPrefsError ?: "The hub is not answering"
                // The safe groups still read from the last good profile snapshot when one exists.
                // A first entry with no snapshot has nothing to paint, so reveal the status line.
                if (wasVisible) paint()
                page.visibility = View.VISIBLE
                return
            }

            prefs = resolvedPrefs
            val profile = nextProfiles?.firstOrNull { it.id == profileId }
            // A missing or failed profile answer stays fail-closed.  We only fall back to Home when
            // the fresh snapshot actually revokes the section that was open; normal adult returns
            // retain their section, scroll position and focused control.
            profileKind = if (profile != null && !profile.kids) "adult" else "kids"
            profileName = profile?.name.orEmpty()
            val root = nextSettings
            if (root != null) {
                val household = root.optJSONObject("settings")
                householdSettingsReady = household != null
                rowList = root.optJSONArray("rows")
                globalRows = household?.optJSONObject("rows")
                // An older compatible Hub may omit a lock block, which explicitly means unlocked.
                // A missing settings object is unknown and remains closed instead.
                lockInfo = household?.optJSONObject("lock") ?: household?.let { JSONObject().put("enabled", false) }
                // The editor edits this profile's rows; the household's list is the floor it cannot lift.
                rowsCfg = resolvedPrefs.optJSONObject("rows") ?: JSONObject()
            } else {
                householdSettingsReady = false
                lockInfo = null
                rowList = null
                globalRows = null
                rowsCfg = resolvedPrefs.optJSONObject("rows") ?: JSONObject()
            }

            status.text = when {
                root == null -> nextSettingsError ?: "Household controls are unavailable"
                nextProfiles == null -> nextProfilesError ?: "Profile access could not be confirmed"
                else -> ""
            }
            paint()
            page.visibility = View.VISIBLE
            // Entering a screen lands on its first content stop, never on chrome. Re-entry uses
            // the viewport snapshot captured above instead of stealing the remote back to the top.
            if (!enteredOnce) {
                enteredOnce = true
                firstRow?.post { firstRow?.requestFocus() }
            }
        }

        api.prefs(profileId) { value, error ->
            if (generation != settingsRefreshVersion || isFinishing || isDestroyed) return@prefs
            nextPrefs = value
            nextPrefsError = error
            prefsDone = true
            completeIfReady()
        }
        api.profiles { value, error ->
            if (generation != settingsRefreshVersion || isFinishing || isDestroyed) return@profiles
            nextProfiles = value
            nextProfilesError = error
            profilesDone = true
            completeIfReady()
        }
        api.settings { value, error ->
            if (generation != settingsRefreshVersion || isFinishing || isDestroyed) return@settings
            nextSettings = value
            nextSettingsError = error
            settingsDone = true
            completeIfReady()
        }
    }

    private var enteredOnce = false

    private var firstRow: View? = null

    /** Unknown profiles take the same least-privilege route as a child until the Hub identifies them. */
    private fun isTrustedAdult(): Boolean = profileKind == "adult"

    private fun paint() {
        // A rebuild must never strand the remote on chrome: the row the viewer was on keeps the
        // ring, and the viewport stays where it was. A first paint lands on the first control.
        val keepKey = deferredFocusKey ?: focusedControlKey()
        val keepScrollY = deferredScrollY ?: contentScroll.scrollY
        // These values are written by a background refresh exactly once.  Clear them before
        // building so an unrelated paint (for example an Auto-update toggle) never resurrects an
        // old scroll location.
        deferredFocusKey = null
        deferredScrollY = null
        rows.removeAllViews()
        firstRow = null
        controlsByKey.clear()
        val p = prefs ?: return

        paintSafeGroups(p)
        paintGuardedGroups()

        val target = controlsByKey[keepKey] ?: firstRow
        rows.post {
            if (isFinishing || isDestroyed) return@post
            contentScroll.scrollTo(0, keepScrollY.coerceAtLeast(0))
            target?.requestFocus()
        }
    }

    // ---- control-centre shell --------------------------------------------------------------

    private fun focusedControlKey(): String? =
        controlsByKey.entries.firstOrNull { it.value.isFocused }?.key

    private fun registerControl(key: String, control: View) {
        if (control.id == View.NO_ID) control.id = View.generateViewId()
        controlsByKey[key] = control
    }

    private fun addGroup(title: String) {
        Screens.section(this, app, rows, title)
    }

    /** A visible explanation is safer than a dead row: it says why an unavailable control is absent. */
    private fun addAvailabilityNotice(title: String, message: String) {
        val plate = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = app.theme.px(66f)
            setPadding(app.theme.px(20f), app.theme.px(9f), app.theme.px(20f), app.theme.px(9f))
            background = Screens.rowFill(app)
        }
        val marker = View(this).apply {
            background = Views.rounded(app.theme.colorOr("panelValue", app.theme.brand), app.theme.px(3f))
        }
        plate.addView(marker, LinearLayout.LayoutParams(app.theme.px(5f), app.theme.px(32f)).apply {
            rightMargin = app.theme.px(14f)
        })
        val copy = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        copy.addView(Views.text(app, "li", 20f, app.theme.ink, title, bold = true, maxLines = 1))
        copy.addView(
            Views.text(app, "meta", 16f, app.theme.ink2, message, maxLines = 1),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = app.theme.px(2f)
            },
        )
        plate.addView(copy, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        rows.addView(
            plate,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = Screens.listGap(app)
            },
        )
    }

    /**
     * The top of the page is everything a person can safely change on this television: how it looks,
     * what this profile hears, who watches and this TV's own controllers and updates. None of it is a
     * household act, so none of it asks for the Settings PIN.
     */
    private fun paintSafeGroups(p: JSONObject) {
        paintAppearance()
        paintPlayback(p)
        paintPeople()
        paintThisTv()
    }

    /** What this person hears and sees. Per-profile, so a child may keep their own language order. */
    private fun paintPlayback(p: JSONObject) {
        addGroup("Play & display")
        addRow("Audio language", languageName(firstOf(p, "audioLanguages")), "Mix this language first") {
            showLanguagePicker("Audio language", "audioLanguages")
        }
        addRow("Subtitles", languageName(firstOf(p, "subtitleLanguages")), "Preferred subtitle language") {
            showLanguagePicker("Subtitles", "subtitleLanguages")
        }
        addRow("Video quality", qualityLabel(p.optString("qualityCap", "auto")), "Best quality to offer") {
            showQualityPicker()
        }
        addRow("Live TV channels", "This person's channels", "Channels and order", chevron = true) {
            startActivity(
                Intent(this, LiveChannelsActivity::class.java)
                    .putExtra(LiveChannelsActivity.EXTRA_PROFILE, profileId)
            )
        }
    }

    /** This TV's own hardware and app: controllers, updates and the build it is running. */
    private fun paintThisTv() {
        addGroup("This TV")
        addRow("Controllers", controllersSummary(), "Pair and test controllers") {
            startActivity(Intent(this, ControllersActivity::class.java))
        }
        // Auto-update (core/Updates.kt): the builds live as GitHub Releases on the project repo.
        // The check is the app's; the install is always the person's — the dialog offers, Android
        // confirms, nothing is ever swapped under a viewer mid-frame.
        addRow("Auto update", if (app.prefs.autoUpdate()) "On" else "Off",
            "Check on startup; install always confirms") {
            app.prefs.setAutoUpdate(!app.prefs.autoUpdate())
            paint()
        }
        addRow("Check for updates",
            "v${Updates.currentVersionName(this)} (${Updates.currentVersionCode(this)})",
            "Check published releases") {
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
    }

    /**
     * The guarded shelf: one row until the household PIN (or the act of creating one) opens it. The
     * sources, network and advanced controls are not hidden by a visual trick — they do not exist in
     * the view tree until [isSourcesUnlocked] is true.
     */
    private fun paintGuardedGroups() {
        addGroup("Household & sources")
        if (!isTrustedAdult()) {
            addAvailabilityNotice(
                "Adult profile required",
                "Switch to an adult profile to manage household settings.",
            )
            return
        }
        if (!householdSettingsReady || householdRefreshPending) {
            addAvailabilityNotice(
                "Household controls are reconnecting",
                "Waiting for the Hub's current settings snapshot.",
            )
            addRow("Refresh household access", "Try again", "Get a current Hub snapshot", chevron = true) {
                load()
            }
            return
        }
        if (!isSourcesUnlocked()) {
            val pinSet = lockInfo?.optBoolean("pinSet") == true
            val enabled = lockInfo?.optBoolean("enabled") == true
            val needsSetup = !pinSet && !enabled
            addRow(
                "Open household settings",
                if (needsSetup) "Set a PIN" else "PIN protected",
                "Sources, network and advanced controls",
                chevron = true,
            ) { requireSources { paint() } }
            addAvailabilityNotice(
                "One PIN for the household",
                if (needsSetup) "Create a PIN here; it guards this shelf from now on."
                else "Enter the household PIN to reveal sources, network and advanced controls.",
            )
            return
        }

        addSourcesRows()
        paintHomeLibraryRows()
        paintNetworkRows()
        paintAdvancedRows()
        addAvailabilityNotice(
            "Unlocked for this visit",
            "The guarded shelf locks again when Settings closes.",
        )
    }

    /** What the house shows: shelves, categories, the shared playlist, the library's name. */
    private fun paintHomeLibraryRows() {
        addGroup("Home & library")
        val shelfRows = rowList
        if (shelfRows != null && shelfRows.length() > 0) {
            val hidden = effectiveHidden()
            val visible = rowIds().filter { isTrustedAdult() || rowScope(it) != "adult" }
            addRow("Home shelves", "${visible.count { it !in hidden }} of ${visible.size} shown",
                "Show, hide or reorder shelves", chevron = true) { showHomeRows() }
        }
        addRow("Categories", "Home shelves", "Genre shelves drawn on Home", chevron = true) {
            startActivity(Intent(this, CategoriesActivity::class.java))
        }
        addRow("Library and search", "Name and search limits", "Hub name and search limits", chevron = true) {
            startActivity(Intent(this, SystemActivity::class.java))
        }
        addRow("Live TV playlist", "Shared playlist and groups", "The M3U every profile tunes", chevron = true) {
            startActivity(Intent(this, LiveSettingsActivity::class.java))
        }
    }

    /**
     * The people who watch. Switching person is safe for anyone; adding or limiting a person is a
     * household act and keeps its PIN even though the group itself is always visible.
     */
    private fun paintPeople() {
        addGroup("Profiles & kids")
        val locked = lockInfo?.optBoolean("enabled") == true
        addRow("People", if (locked) "PIN protected" else "Adults and children",
            "Add, rename or change who is watching", chevron = true) {
            requireAdmin { startActivity(Intent(this, ManageProfilesActivity::class.java)) }
        }
        addRow("Switch person", "Who's watching", "Change the active profile", chevron = true) {
            startActivity(Intent(this, ProfilesActivity::class.java))
        }
        if (!isTrustedAdult()) {
            addAvailabilityNotice(
                "Household people are protected",
                "An adult profile is needed to add or limit anyone.",
            )
        }
    }

    /**
     * Appearance is the owner's headline complaint: theme and readability used to sit behind the
     * Settings PIN, so the people most bothered by a mixed-up screen could not fix it. They now open
     * for anyone. A shared theme still falls back to a local choice if the Hub refuses the write.
     */
    private fun paintAppearance() {
        addGroup("Appearance")
        addRow("Theme", themeName(),
            "This TV look; shared where supported", chevron = true) {
            pickTheme()
        }
        val installedPacks = Theme.catalog(this, assets).count { it.installed }
        addRow("Extra themes",
            if (installedPacks == 0) "None installed" else "$installedPacks installed",
            "Themes installed on this TV", chevron = true) {
            showPackThemes()
        }
        // One multiplier governs the complete native rhythm: type, tiles, gaps and hero all grow
        // together. A person can make the interface readable without changing anyone else's theme.
        val zoomSteps = listOf(1f, 1.15f, 1.3f, 1.5f)
        val zoomNames = listOf("100%", "115%", "130%", "150%")
        val z = app.prefs.uiZoom()
        val zoomNow = zoomSteps.indices.minByOrNull { kotlin.math.abs(zoomSteps[it] - z) } ?: 0
        addRow("Text size", zoomNames[zoomNow],
            "Make everything bigger or smaller", chevron = true) {
            showPicker("Text size",
                zoomSteps.mapIndexed { i, v -> v.toString() to zoomNames[i] },
                z.toString()) { picked ->
                val value = picked.toFloatOrNull() ?: return@showPicker
                app.prefs.setUiZoom(value)
                (application as ScenixApp).reloadTheme()
                window.decorView.postDelayed({
                    startActivity(
                        Intent(this, ProfilesActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    )
                }, 120L)
            }
        }
    }

    /** Where this library comes from and where its files live. Guarded with the rest of the shelf. */
    private fun paintNetworkRows() {
        addGroup("Network & hub")
        addRow("Hub connection", backendSummary(),
            "Change Hub or use standalone", chevron = true) {
            startActivity(Intent(this, MainActivity::class.java).putExtra("setup", true))
        }
        if (app.prefs.backendChoice() != Prefs.BACKEND_STANDALONE) {
            addRow("Update Hub", "Check its build", "The Hub restarts only after it confirms a safe update") {
                status.text = "Checking Hub…"
                val base = HubEndpoint.base(this)
                val token = app.prefs.hubToken()
                Thread {
                    val message = when {
                        base.isBlank() -> "This TV has no Hub to ask"
                        else -> {
                            val state = HubUpdate.status(base, token)
                            when {
                                state == null -> "This Hub does not support managed updates"
                                !state.git -> state.blocked ?: "This Hub is not a git checkout"
                                state.behind == 0 -> "The Hub is already current (${state.commit})"
                                !state.canUpdate -> "The Hub is ${state.behind} commits behind: ${state.blocked}"
                                else -> {
                                    val update = HubUpdate.updateNow(base, token)
                                    if (update?.optBoolean("updated") == true) {
                                        "The Hub updated and will restart shortly"
                                    } else {
                                        "The Hub declined: ${update?.optString("reason") ?: "no answer"}"
                                    }
                                }
                            }
                        }
                    }
                    runOnUiThread {
                        if (!isFinishing && !isDestroyed) status.text = message
                    }
                }.start()
            }
        }

        // Only standalone has files on this machine. Against a Hub, a folder row would imply a
        // library this TV cannot actually read, so it remains absent instead of looking broken.
        if (app.prefs.backendChoice() == Prefs.BACKEND_STANDALONE) {
            val local = com.scenix.tv.core.LocalHub.get(this).localStatus()
            val files = local.optInt("files", 0)
            val folder = local.optString("name", "").ifBlank { "no folder chosen" }
            val value = when {
                !local.optBoolean("granted", false) -> "Choose a folder"
                files > 0 -> "$files files · $folder"
                else -> folder
            }
            addRow("Local files", value, "Files stored on this TV") { pickLocalFolder() }
        }
    }

    /**
     * The guarded shelf opens once per visit. If no PIN exists yet, the first entry creates one;
     * afterwards the shared unlock asks for it once. The revealed rows keep the destinations the old
     * Sources page had — Source packs, Add-ons, Debrid — so nothing else moves.
     */
    private fun isSourcesUnlocked(): Boolean {
        if (!householdSettingsReady || householdRefreshPending) return false
        if (!adminUnlocked) return false
        val sessionStillValid = app.prefs.adminSessionFor(HubEndpoint.base(this)).isNotBlank()
        if (adminUnlockUsesSession && !sessionStillValid) return false
        val pinSet = lockInfo?.optBoolean("pinSet") == true
        val enabled = lockInfo?.optBoolean("enabled") == true
        return pinSet || enabled
    }

    private fun requireSources(onUnlocked: () -> Unit) {
        if (householdRefreshPending) {
            status.text = "Refreshing household access…"
            return
        }
        if (!householdSettingsReady) {
            status.text = "Household controls are unavailable — try again"
            return
        }
        if (isSourcesUnlocked()) {
            onUnlocked()
            return
        }
        val pinSet = lockInfo?.optBoolean("pinSet") == true
        val enabled = lockInfo?.optBoolean("enabled") == true
        if (!pinSet && !enabled) {
            showPinPad("Create a PIN for Sources", "Choose 4 to 8 digits", gate = false) { entered ->
                if (entered.length !in 4..8) { pinError("Use 4 to 8 digits"); return@showPinPad }
                api.saveSettingsLock(true, entered, null) { root, error ->
                    if (isFinishing || isDestroyed) return@saveSettingsLock
                    if (error != null) { pinError(error); return@saveSettingsLock }
                    lockInfo = root?.optJSONObject("lock") ?: JSONObject().put("enabled", true)
                    rememberAdminProof(true)
                    closePinPad()
                    onUnlocked()
                }
            }
            return
        }
        showPinPad("PIN required for Sources", "Enter the household PIN", gate = false) { entered ->
            api.unlockSettings(entered) { ok, error ->
                if (isFinishing || isDestroyed) return@unlockSettings
                if (ok) {
                    rememberAdminProof(true)
                    closePinPad()
                    onUnlocked()
                } else {
                    pinError(error ?: "That PIN is not right")
                }
            }
        }
    }

    private fun addSourcesRows() {
        addGroup("Sources & add-ons")
        addRow("Source packs", "Extra packs for audio and art", "Fill preferred audio gaps", chevron = true) {
            startActivity(Intent(this, SourcePacksActivity::class.java))
        }
        addRow("Add-ons", "Sources this TV reads", "Review and turn sources on or off", chevron = true) {
            startActivity(
                Intent(this, AddonsActivity::class.java)
                    .putExtra(AddonsActivity.EXTRA_PROFILE, profileId)
                    // The PIN was just proved here; carry that so the directory does not ask twice.
                    .putExtra(AddonsActivity.EXTRA_SETTINGS_PROVED, true)
            )
        }
        addRow("Debrid", "Cached copies", "Provider, token and switch", chevron = true) {
            startActivity(Intent(this, DebridActivity::class.java))
        }
    }

    /** The lock itself, and the one number it keeps. */
    private fun paintAdvancedRows() {
        val adminOn = lockInfo?.optBoolean("enabled") == true
        addGroup("Advanced")
        addRow("Settings lock", if (adminOn) "On" else "Off",
            "One PIN for the guarded shelf") {
            changeLock()
        }
        if (adminOn) {
            addRow("Change PIN", "Choose a new PIN", "The current PIN is needed", chevron = true) {
                changePin()
            }
        }
        // The component catalogue exists only in debug builds. It is how the design-system parity
        // gate captures the real components; a release television has no use for a studio.
        if ((applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            addRow("Component studio", "Design system catalogue",
                "Inspect real theme components", chevron = true) {
                startActivity(Intent(this, StudioActivity::class.java))
            }
        }
        addAvailabilityNotice(
            if (adminOn) "The household lock is on" else "The household lock is off",
            if (adminOn) "Guarded areas ask for the PIN once per visit."
            else "Set a PIN here so a child cannot change what the house can watch.",
        )
    }

    private fun themeName(): String {
        val picked = Theme.catalog(this, assets).firstOrNull { it.id == app.prefs.theme() }
        return when {
            picked == null -> app.prefs.theme()
            picked.pack.isBlank() -> picked.name
            else -> picked.name + " · " + picked.pack
        }
    }

    private fun backendSummary(): String {
        val backend = app.prefs.backendChoice()
        val hubUrl = app.prefs.hubUrl(getString(R.string.hub_url))
        val hubName = app.prefs.discoveredHubs().firstOrNull { it.url == hubUrl }?.name.orEmpty()
        return when {
            backend == Prefs.BACKEND_STANDALONE -> "Standalone"
            hubName.isNotBlank() -> "Hub · $hubName"
            else -> "Hub · $hubUrl"
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
        if (resultCode != RESULT_OK) return
        if (requestCode == REQ_PACK_URL) {
            val url = data?.getStringExtra(TextEntryActivity.EXTRA_RESULT)?.trim().orEmpty()
            if (url.isNotBlank()) installPack(url)
            return
        }
        if (requestCode != REQ_LOCAL_TREE) return
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

    private fun addRow(
        label: String,
        value: String,
        note: String,
        chevron: Boolean = false,
        action: () -> Unit,
    ) {
        val row = controlRow(label, value, note, chevron, action)
        if (row.id == View.NO_ID) row.id = View.generateViewId()
        // Group labels and availability explanations are never remote stops. Link only real
        // controls so DOWN walks every setting and does not jump into the fixed top chrome.
        var previous: View? = null
        for (index in rows.childCount - 1 downTo 0) {
            val candidate = rows.getChildAt(index)
            if (candidate.isFocusable) {
                previous = candidate
                break
            }
        }
        previous?.let {
            it.nextFocusDownId = row.id
            row.nextFocusUpId = it.id
        }
        row.nextFocusDownId = row.id
        rows.addView(
            row,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = Screens.listGap(app)
            },
        )
        if (firstRow == null) firstRow = row
        registerControl("row:$label", row)
    }

    private fun controlRow(
        label: String,
        value: String,
        note: String,
        chevron: Boolean,
        action: () -> Unit,
    ): View {
        val radius = Screens.listRadius(app)
        val base = Screens.rowFill(app)
        val focused = Screens.rowFillFocused(app) ?: Views.rounded(
            app.theme.colorOr("listFillFocused", app.theme.surface2),
            radius,
            maxOf(1, app.theme.focusRingPx),
            app.theme.focusRingColor,
        )
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = maxOf(Screens.listHeight(app), app.theme.px(76f))
            setPadding(app.theme.px(20f), app.theme.px(10f), app.theme.px(18f), app.theme.px(10f))
            background = base
            isFocusable = true
            isClickable = true
            contentDescription = listOf(label, value, note)
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .joinToString(". ")
            setOnClickListener { action() }
        }
        val marker = View(this).apply {
            background = Views.rounded(app.theme.colorOr("panelValue", app.theme.brand), app.theme.px(3f))
        }
        row.addView(marker, LinearLayout.LayoutParams(app.theme.px(5f), app.theme.px(34f)).apply {
            rightMargin = app.theme.px(14f)
        })
        val copy = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val title = Views.text(app, "li", 23f, app.theme.ink, label, bold = true, maxLines = 1)
        copy.addView(title)
        val noteView = Views.text(app, "meta", 16f, app.theme.ink2, note, maxLines = 1)
        if (note.isNotBlank()) {
            copy.addView(
                noteView,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = app.theme.px(2f)
                },
            )
        }
        row.addView(copy, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        var valueView: TextView? = null
        if (value.isNotBlank()) {
            val pill = Views.text(
                app,
                "liMeta",
                17f,
                app.theme.colorOr("panelValue", app.theme.brand),
                value,
                bold = true,
                gravity = Gravity.CENTER,
                maxLines = 1,
            ).apply {
                maxWidth = app.theme.px(300f)
                setPadding(app.theme.px(10f), app.theme.px(5f), app.theme.px(10f), app.theme.px(5f))
                background = Views.rounded(app.theme.colorOr("chipBg", app.theme.surface2), app.theme.radius("badge", 8f))
            }
            valueView = pill
            row.addView(
                pill,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    leftMargin = app.theme.px(14f)
                },
            )
        }
        if (chevron) {
            row.addView(
                Views.text(app, "li", 26f, app.theme.ink3, "›", gravity = Gravity.CENTER),
                LinearLayout.LayoutParams(app.theme.px(22f), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    leftMargin = app.theme.px(8f)
                },
            )
        }
        Focus.attach(
            row,
            row,
            app,
            radius,
            scale = false,
            elevate = false,
            glowInset = app.theme.focusGlowInsetSm,
            control = true,
            onFocus = { hasFocus ->
                row.background = if (hasFocus) focused else base
                title.setTextColor(if (hasFocus) app.theme.colorOr("wallInk", app.theme.ink) else app.theme.ink)
                noteView.setTextColor(if (hasFocus) app.theme.colorOr("wallInk2", app.theme.ink2) else app.theme.ink2)
                valueView?.setTextColor(
                    if (hasFocus) app.theme.colorOr("wallInk2", app.theme.ink2)
                    else app.theme.colorOr("panelValue", app.theme.brand),
                )
            },
        )
        return row
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
            if (!isTrustedAdult() && rowScope(id) == "adult") continue
            options.add(id to rowTitle(id) + if (id in hidden) "  ·  Hidden" else "  ·  On Home")
        }
        showPicker("Home shelves", options, "") { id -> showRowActions(id) }
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
        options.add("" to "Not set")
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
        showPicker("Video quality", options, p.optString("qualityCap", "auto")) { v ->
            write(JSONObject().put("qualityCap", v))
        }
    }

    private fun showPicker(title: String, options: List<Pair<String, String>>, current: String, onPick: (String) -> Unit) {
        if (picker == null) pickerReturn = currentFocus
        closePicker()
        pickerReturn = currentFocus ?: pickerReturn
        val layer = FrameLayout(this).apply {
            setBackgroundColor(Screens.dimColor(app))
            isClickable = true
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(app.theme.px(28f), app.theme.px(22f), app.theme.px(28f), app.theme.px(22f))
            background = Screens.modalFill(app)
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
            val row = Screens.pickerRow(this, app, label, selected) {
                closePicker()
                onPick(value)
            }
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
                    // A held ENTER autorepeats every ~60 ms; each repeat would be another PIN
                    // attempt against the hub's rate limiter, locking the owner out of their own
                    // settings. Only the first press submits.
                    KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                        if (event.repeatCount == 0) submitPin()
                        return true
                    }
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
                // A picker is a one-column modal.  Consuming horizontal input prevents Android's
                // focus search from escaping through the overlay to a Settings row behind it.
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> return true
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    // Same held-key guard as the PIN pad: a toggle row stays focused after its first
                    // click, and every repeat would flip it again.
                    if (event.repeatCount > 0) return true
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
        // A return from a child screen keeps its previous composition visible while the Hub
        // refreshes. Do not let that preserved UI become a stale authority: the next protected
        // action waits for the fresh profile and household snapshot.
        if (householdRefreshPending) {
            status.text = "Refreshing household access…"
            return
        }
        if (!householdSettingsReady) {
            status.text = "Household controls are unavailable — try again"
            return
        }
        val sessionStillValid = app.prefs.adminSessionFor(HubEndpoint.base(this)).isNotBlank()
        if (lockInfo?.optBoolean("enabled") != true ||
            (adminUnlocked && (!adminUnlockUsesSession || sessionStillValid))
        ) {
            block()
            return
        }
        showPinPad("Admin PIN required", "Enter the household settings PIN", gate = false) { entered ->
            api.unlockSettings(entered) { ok, error ->
                if (isFinishing || isDestroyed) return@unlockSettings
                if (ok) {
                    rememberAdminProof(true)
                    closePinPad()
                    block()
                } else {
                    pinError(error ?: "That PIN is not right")
                }
            }
        }
    }

    /** Keep visit-local access in step with the Hub capability minted by an unlock or lock change. */
    private fun rememberAdminProof(unlocked: Boolean) {
        adminUnlocked = unlocked
        adminUnlockUsesSession = unlocked &&
            app.prefs.adminSessionFor(HubEndpoint.base(this)).isNotBlank()
    }

    private fun changePin() {
        showPinPad("Change the settings PIN", "Enter the current PIN", gate = false) { current ->
            api.unlockSettings(current) { ok, _ ->
                if (isFinishing || isDestroyed) return@unlockSettings
                if (!ok) { pinError("That PIN is not right"); return@unlockSettings }
                showPinPad("Change the settings PIN", "Choose 4 to 8 digits", gate = false) freshPin@{ fresh ->
                    if (fresh.length !in 4..8) { pinError("Use 4 to 8 digits"); return@freshPin }
                    api.saveSettingsLock(true, fresh, current) { root, error ->
                        if (isFinishing || isDestroyed) return@saveSettingsLock
                        if (error != null) { pinError(error); return@saveSettingsLock }
                        lockInfo = root?.optJSONObject("lock") ?: JSONObject().put("enabled", true)
                        rememberAdminProof(true)
                        closePinPad()
                        paint()
                    }
                }
            }
        }
    }

    private fun pickTheme() {
        val choices = Theme.catalog(this, assets)
        showPicker("Theme", choices.map {
            val detail = if (it.pack.isBlank()) it.name else it.name + " · " + it.pack
            it.id to detail + if (it.sharedWithWeb) " · TV + web" else " · This TV only"
        }, app.prefs.theme()) { id ->
            val choice = choices.firstOrNull { it.id == id } ?: return@showPicker
            if (!choice.sharedWithWeb) {
                // A device-only layout is never written into the household's browser setting. That
                // prevents a connected browser from silently falling back to another look.
                applyTheme(id)
                return@showPicker
            }
            status.text = "Saving theme…"
            api.saveSettings(JSONObject().put("settings", JSONObject().put("theme", id))) { _, _ ->
                if (isFinishing || isDestroyed) return@saveSettings
                // Appearance is safe for anyone to change. If the household lock refuses a shared
                // write, the TV still wears the choice locally instead of sending the owner to a
                // PIN prompt they do not need for their own screen.
                applyTheme(id)
            }
        }
    }

    /** Rebuild every native surface only after a local or shared choice has been accepted. */
    private fun applyTheme(id: String) {
        app.prefs.setTheme(id)
        (application as ScenixApp).reloadTheme()
        startActivity(
            Intent(this, ProfilesActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
    }

    /**
     * The installed tier of the pack system: token reskins of the curated packs, each one JSON at
     * `files/packs/<id>.json` naming its base in `basedOn`. A reskin paints colours, radii,
     * spacing, type and focus bands — playback and the layout itself stay in the curated pack,
     * so an installed theme can never make Home unusable or change streaming behavior.
     */
    private fun showPackThemes() {
        val installed = Theme.catalog(this, assets).filter { it.installed }
        val options = ArrayList<Pair<String, String>>()
        options.add("add" to "Install a token-only theme from a secure URL")
        for (c in installed) options.add("remove:" + c.id to "Remove " + c.name)
        showPicker("Extra themes", options, "") { value ->
            when {
                value == "add" -> startActivityForResult(
                    Intent(this, TextEntryActivity::class.java)
                        .putExtra(TextEntryActivity.EXTRA_TITLE, "Install a theme from a link")
                        .putExtra(TextEntryActivity.EXTRA_HINT, "https://…/pack.json"),
                    REQ_PACK_URL,
                )
                value.startsWith("remove:") -> removePack(value.removePrefix("remove:"))
            }
        }
    }

    private fun installPack(url: String) {
        status.text = "Fetching the pack…"
        Thread {
            val outcome = try {
                val pack = fetchPack(url)
                val id = validatePack(pack)
                writePackAtomically(id, pack)
                null
            } catch (t: Throwable) {
                t.message?.takeIf { it.startsWith("Theme:") }?.removePrefix("Theme: ")
                    ?: "The theme could not be fetched or read"
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                status.text = outcome ?: "Theme installed locally — choose it under Theme"
                paint()
            }
        }.start()
    }

    /**
     * Remote packs are deliberately data-only and local to this television. Fetching enforces
     * HTTPS, public DNS answers, bounded redirects and a hard byte cap before JSON parsing; a
     * custom colour scheme must never become a local-network request primitive or a memory spike.
     */
    private fun fetchPack(rawUrl: String): JSONObject {
        var uri = try { java.net.URI(rawUrl.trim()) } catch (_: Throwable) {
            throw IllegalArgumentException("Theme: Enter a valid HTTPS URL")
        }
        repeat(MAX_PACK_REDIRECTS + 1) {
            validatePackUri(uri)
            val conn = try {
                (uri.toURL().openConnection() as? java.net.HttpURLConnection)
                    ?: throw IllegalArgumentException("Theme: The URL is not an HTTPS file")
            } catch (t: IllegalArgumentException) {
                throw t
            } catch (_: Throwable) {
                throw IllegalArgumentException("Theme: The theme host could not be reached")
            }
            try {
                conn.instanceFollowRedirects = false
                conn.connectTimeout = 8_000
                conn.readTimeout = 8_000
                conn.setRequestProperty("Accept", "application/json")
                val code = conn.responseCode
                if (code in 300..399) {
                    val location = conn.getHeaderField("Location")
                        ?: throw IllegalArgumentException("Theme: The redirect has no destination")
                    uri = try { uri.resolve(location) } catch (_: Throwable) {
                        throw IllegalArgumentException("Theme: The redirect is not valid")
                    }
                    return@repeat
                }
                if (code !in 200..299) throw IllegalArgumentException("Theme: The host returned $code")
                val contentType = conn.contentType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
                if (contentType.isNotBlank() && contentType !in setOf("application/json", "text/json", "text/plain")) {
                    throw IllegalArgumentException("Theme: The URL did not return a JSON theme")
                }
                val text = conn.inputStream.use { readLimited(it, MAX_PACK_BYTES) }
                return try {
                    JSONObject(text)
                } catch (_: Throwable) {
                    throw IllegalArgumentException("Theme: The file is not valid JSON")
                }
            } finally {
                conn.disconnect()
            }
        }
        throw IllegalArgumentException("Theme: Too many redirects")
    }

    private fun validatePackUri(uri: java.net.URI) {
        val host = uri.host?.trim().orEmpty()
        if (!uri.isAbsolute || !uri.scheme.equals("https", ignoreCase = true) || host.isBlank() ||
            uri.userInfo != null || (uri.port != -1 && uri.port != 443)
        ) {
            throw IllegalArgumentException("Theme: Use a public HTTPS URL on port 443")
        }
        val addresses = try { java.net.InetAddress.getAllByName(host).toList() } catch (_: Throwable) {
            throw IllegalArgumentException("Theme: The theme host could not be resolved")
        }
        if (addresses.isEmpty() || addresses.any { !isPublicPackAddress(it) }) {
            throw IllegalArgumentException("Theme: Themes must be hosted on a public address")
        }
    }

    private fun isPublicPackAddress(address: java.net.InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress
        ) return false
        val octets = address.address
        if (octets.size == 4) {
            val a = octets[0].toInt() and 0xff
            val b = octets[1].toInt() and 0xff
            // RFC 6598 shared space and the documentation ranges are not public theme hosts.
            if ((a == 100 && b in 64..127) || (a == 192 && b == 0) ||
                (a == 198 && (b == 18 || b == 19 || b == 51)) ||
                (a == 203 && b == 0)
            ) return false
        } else if (octets.size == 16 && ((octets[0].toInt() and 0xfe) == 0xfc)) {
            // RFC 4193 IPv6 unique-local addresses are private even though Java does not always
            // classify them as site-local.
            return false
        }
        return true
    }

    private fun readLimited(input: java.io.InputStream, limit: Int): String {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(4_096)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (output.size() + read > limit) {
                throw IllegalArgumentException("Theme: The file is larger than 64 KB")
            }
            output.write(buffer, 0, read)
        }
        return output.toString(Charsets.UTF_8.name())
    }

    /** Only visual scalar tokens are accepted; layouts, playback settings and executable data are not. */
    private fun validatePack(pack: JSONObject): String {
        val allowedRoots = setOf("id", "name", "basedOn", "colors", "radii", "spacing", "type", "focus")
        val roots = pack.keys()
        while (roots.hasNext()) {
            if (roots.next() !in allowedRoots) {
                throw IllegalArgumentException("Theme: Only visual token groups are allowed")
            }
        }
        val id = pack.optString("id").trim()
        if (!Regex("^[a-z][a-z0-9-]{1,31}$").matches(id)) {
            throw IllegalArgumentException("Theme: The id must use lowercase letters, digits and dashes")
        }
        val name = pack.optString("name", id).trim()
        if (name.isBlank() || name.length > 48 || name.any { it.isISOControl() }) {
            throw IllegalArgumentException("Theme: The name must be 1 to 48 readable characters")
        }
        val baseId = pack.optString("basedOn").trim()
        if (Theme.catalog(assets).none { it.id == baseId }) {
            throw IllegalArgumentException("Theme: Choose one of this TV's shipped base themes")
        }
        if (Theme.catalog(this, assets).any { it.id == id }) {
            throw IllegalArgumentException("Theme: This id is already installed")
        }
        var tokenCount = 0
        for (group in listOf("colors", "radii", "spacing", "type", "focus")) {
            if (!pack.has(group)) continue
            val values = pack.optJSONObject(group)
                ?: throw IllegalArgumentException("Theme: $group must be an object")
            if (values.length() > MAX_PACK_TOKENS) {
                throw IllegalArgumentException("Theme: $group has too many values")
            }
            val keys = values.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                if (!Regex("^[A-Za-z][A-Za-z0-9]{0,47}$").matches(key)) {
                    throw IllegalArgumentException("Theme: $group has an invalid token name")
                }
                validatePackToken(group, key, values.get(key))
                tokenCount++
            }
        }
        if (tokenCount == 0) throw IllegalArgumentException("Theme: Add at least one visual token")
        return id
    }

    private fun validatePackToken(group: String, key: String, value: Any) {
        if (group == "colors" || (group == "focus" && key == "keylineColor")) {
            if (value !is String || !isThemeColor(value)) {
                throw IllegalArgumentException("Theme: $key must be a valid colour")
            }
            return
        }
        if (group == "focus" && key == "glowAlphas") {
            val levels = value as? JSONArray
                ?: throw IllegalArgumentException("Theme: glowAlphas must be three numbers")
            if (levels.length() != 3 || (0 until levels.length()).any {
                    val n = levels.opt(it)
                    n !is Number || n.toDouble() !in 0.0..255.0
                }) {
                throw IllegalArgumentException("Theme: glowAlphas must be three values from 0 to 255")
            }
            return
        }
        if (value !is Number || !java.lang.Double.isFinite(value.toDouble())) {
            throw IllegalArgumentException("Theme: $key must be a number")
        }
        val n = value.toDouble()
        val valid = when (group) {
            "radii" -> n in 0.0..400.0
            "spacing" -> n in 0.0..1_200.0
            "type" -> n in 8.0..320.0
            "focus" -> when (key) {
                "scale", "rowScale" -> n in 1.0..1.16
                "durationMs" -> n in 0.0..500.0
                "ringWidth", "glassPad", "glowInset", "glowInsetSm", "keylineWidth" -> n in 0.0..96.0
                "shadowElevation" -> n in 0.0..80.0
                "glass", "glowAlpha" -> n in 0.0..8.0
                else -> false
            }
            else -> false
        }
        if (!valid) throw IllegalArgumentException("Theme: $key is outside the safe visual range")
    }

    private fun isThemeColor(value: String): Boolean {
        val text = value.trim()
        if (Regex("^#[0-9a-fA-F]{3}([0-9a-fA-F]{3}|[0-9a-fA-F]{5})?$").matches(text)) return true
        val match = Regex("^rgba?\\(([^)]*)\\)$", RegexOption.IGNORE_CASE).matchEntire(text) ?: return false
        val parts = match.groupValues[1].split(',').map { it.trim() }
        if (parts.size !in 3..4) return false
        val rgb = parts.take(3).all { it.toIntOrNull()?.let { n -> n in 0..255 } == true }
        if (!rgb) return false
        return parts.size == 3 || parts[3].toDoubleOrNull()?.let { it in 0.0..1.0 } == true
    }

    private fun writePackAtomically(id: String, pack: JSONObject) {
        val dir = java.io.File(filesDir, "packs")
        if (!dir.exists() && !dir.mkdirs()) throw IllegalArgumentException("Theme: The local theme folder is unavailable")
        val destination = java.io.File(dir, "$id.json")
        if (destination.exists()) throw IllegalArgumentException("Theme: This id is already installed")
        val temporary = java.io.File.createTempFile(".$id-", ".json", dir)
        try {
            temporary.outputStream().use { out ->
                out.write(pack.toString().toByteArray(Charsets.UTF_8))
                out.flush()
                try { (out as? java.io.FileOutputStream)?.fd?.sync() } catch (_: Throwable) { }
            }
            if (!temporary.renameTo(destination)) {
                throw IllegalArgumentException("Theme: The theme could not be stored")
            }
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    private fun removePack(id: String) {
        // Read the base before deleting: if this pack is the one being worn, the TV falls back to
        // the curated pack it was painting, not to some unrelated default.
        val file = java.io.File(java.io.File(filesDir, "packs"), "$id.json")
        val baseId = try {
            JSONObject(file.readText()).optString("basedOn").trim()
        } catch (t: Throwable) {
            ""
        }
        try {
            file.delete()
        } catch (t: Throwable) {
            status.text = "The pack could not be removed"
        }
        if (app.prefs.theme() == id) {
            // A screen cannot keep painting a pack that no longer exists: fall back to its base
            // and rebuild the task, the way switching themes does.
            val fallback = Theme.catalog(this, assets).firstOrNull { it.id == baseId }?.id
                ?: Theme.catalog(this, assets).first { !it.installed }.id
            app.prefs.setTheme(fallback)
            (application as ScenixApp).reloadTheme()
            startActivity(
                Intent(this, ProfilesActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            )
        }
        paint()
    }

    private fun changeLock() {
        if (householdRefreshPending) {
            status.text = "Refreshing household access…"
            return
        }
        val on = lockInfo?.optBoolean("enabled") == true
        if (on) {
            showPinPad("Turn off the settings lock", "Enter the current PIN", gate = false) { entered ->
                api.saveSettingsLock(false, null, entered) { root, error ->
                    if (isFinishing || isDestroyed) return@saveSettingsLock
                    if (error != null) { pinError(error); return@saveSettingsLock }
                    lockInfo = root?.optJSONObject("lock") ?: JSONObject().put("enabled", false)
                    rememberAdminProof(false)
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
                    rememberAdminProof(true)
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
                    rememberAdminProof(true)
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
        val layer = FrameLayout(this).apply {
            // The keypad is a full-screen page, not a small modal: it stands on the pack's own
            // ground so every ink on it stays readable in this pack.
            val ground = Screens.wall(app)
            if (ground != null) background = ground else setBackgroundColor(app.theme.bg)
        }
        val glassKeys = app.theme.homeLayout.hero ==
            com.scenix.tv.core.HomeLayoutSpec.Hero.GLASS_CARDS
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(app.theme.px(48f), app.theme.px(36f), app.theme.px(48f), app.theme.px(24f))
            // The pad wears the same deep glass plate as the household's PIN gate: the pack's
            // white inks were standing bare on the light wall and washing out (2026-11-02).
            if (glassKeys) {
                val plateR = app.theme.radius("hero", 26f)
                background = Views.rounded(app.theme.color("gatePlate", "rgba(8,16,28,0.88)"),
                    plateR, app.theme.px(1.5f), app.theme.color("glassRim", "rgba(255,255,255,0.38)"))
                elevation = app.theme.space("plateElevation", 16f) / 2f
            }
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
                val b = Views.text(app, "li", 24f,
                    app.theme.colorOr("keyInk", app.theme.ink), label, bold = true, gravity = Gravity.CENTER)
                b.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                    app.theme.px(if (label.length > 1) 17f else 26f).toFloat())
                val keyRadius = app.theme.radius("key", 12f)
                b.background = if (glassKeys) Views.rounded(
                    app.theme.color(if (label == "OK") "chipFillActive" else "keyGlass",
                        "rgba(255,255,255,0.10)"),
                    keyRadius, app.theme.px(1f), app.theme.color("glassRim", "rgba(255,255,255,0.38)"))
                else Views.rounded(app.theme.colorOr("keyBg", app.theme.surface2), keyRadius)
                b.isFocusable = true
                b.isClickable = true
                b.setOnClickListener {
                    when (label) {
                        "CLR" -> { pinBuf.setLength(0); renderPin() }
                        "OK" -> submitPin()
                        else -> addPinDigit(label.toInt())
                    }
                }
                Focus.attach(b, b, app, keyRadius, scale = false, elevate = false,
                    glowInset = app.theme.focusGlowInsetSm, control = true)
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

    // ---- phone pairing rail ---------------------------------------------------------------------

    /**
     * The pinned rail. It is part of the page chrome, not a settings row: a person opening Settings
     * sees a code big enough to scan before they read a single label, and the phone it opens can
     * drive the D-pad and type into search. It stays outside the ScrollView so scrolling never takes
     * the link away.
     */
    private fun buildPhoneRail(): LinearLayout {
        val rail = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(app.theme.px(18f), app.theme.px(16f), app.theme.px(18f), app.theme.px(18f))
            background = Views.rounded(
                app.theme.colorOr("settingsContext", app.theme.colorOr("listFill", app.theme.surface)),
                app.theme.radius("listItem", 12f),
                maxOf(1, app.theme.px(1f)),
                app.theme.colorOr("listStroke", app.theme.line),
            )
        }
        val heading = Views.text(app, "meta", 14f, app.theme.colorOr("panelValue", app.theme.brand),
            "CONNECT YOUR PHONE", bold = true, gravity = Gravity.CENTER, maxLines = 1)
        heading.letterSpacing = 0.14f
        rail.addView(heading, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = app.theme.px(4f) })
        rail.addView(
            Views.text(app, "li", 20f, app.theme.ink, "Your phone is the remote",
                bold = true, gravity = Gravity.CENTER, maxLines = 2),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = app.theme.px(10f) },
        )
        remoteQrHost = FrameLayout(this).apply {
            // Reserve the code's square before the async session answers, so the rail never jumps
            // when the bitmap replaces the placeholder.
            minimumHeight = maxOf(app.theme.px(300f), 260)
        }
        rail.addView(remoteQrHost, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        remoteUrlView = Views.text(app, "meta", 13f, app.theme.ink2, "", gravity = Gravity.CENTER, maxLines = 3)
        rail.addView(remoteUrlView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = app.theme.px(10f) })
        remoteCodeView = Views.text(app, "liMeta", 22f,
            app.theme.colorOr("panelValue", app.theme.brand), "",
            bold = true, gravity = Gravity.CENTER, maxLines = 1)
        rail.addView(remoteCodeView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = app.theme.px(6f) })
        remoteStatusView = Views.text(app, "meta", 15f, app.theme.ink2,
            "Preparing the link…", gravity = Gravity.CENTER, maxLines = 2)
        rail.addView(remoteStatusView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = app.theme.px(4f) })
        rail.addView(
            Views.text(app, "meta", 13f, app.theme.ink3,
                "D-pad, OK/Back, typing and dictation work from the phone — on this screen or any " +
                    "other, while Scenix is open on this TV.",
                gravity = Gravity.CENTER, maxLines = 4),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = app.theme.px(8f) },
        )
        return rail
    }

    /** Draw the relay's current pairing (or the honest fallback) into the rail. */
    private fun renderPhoneRail() {
        val host = remoteQrHost ?: return
        val pairing = remotePairing ?: return
        host.removeAllViews()
        if (pairing.origin.isBlank()) {
            remoteUrlView?.text = "No network address yet"
            remoteCodeView?.text = ""
            remoteStatusView?.text = pairing.status
            return
        }
        val url = pairing.url
        val bitmap = url?.let { PhonePairing.renderQr(it, maxOf(app.theme.px(340f), 300)) }
        if (bitmap != null) {
            val plate = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(app.theme.px(10f), app.theme.px(10f), app.theme.px(10f), app.theme.px(10f))
                background = Views.rounded(android.graphics.Color.WHITE, app.theme.radius("card", 10f))
            }
            val image = android.widget.ImageView(this).apply {
                setImageBitmap(bitmap)
                contentDescription = if (pairing.live)
                    "QR code to open the Scenix phone remote"
                else
                    "QR code to open Scenix Settings on a phone"
                scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
            }
            val side = maxOf(app.theme.px(300f), 260)
            plate.addView(image, LinearLayout.LayoutParams(side, side))
            host.addView(plate, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { gravity = Gravity.CENTER_HORIZONTAL })
        } else {
            host.addView(Views.text(app, "meta", 16f, app.theme.ink2,
                "The code could not be drawn", gravity = Gravity.CENTER, maxLines = 2))
        }
        remoteUrlView?.text = url.orEmpty()
        remoteCodeView?.text = if (pairing.live) "CODE  ${pairing.code}" else ""
        remoteStatusView?.text = pairing.status
    }

    private fun remoteUrl(origin: String): String =
        origin.trimEnd('/') + "/remote?t=" + remoteToken + "&c=" + remoteCode

    /**
     * The phone's typed text feeds the PIN pad while it is open; otherwise the remote's default
     * applies (an on-screen field, or Search), which is what a viewer holding the phone means.
     */
    override fun onRemoteText(text: String): Boolean {
        if (pinLayer == null) return false
        for (ch in text) {
            if (ch.isDigit() && pinBuf.length < 8) addPinDigit(ch - '0')
        }
        return true
    }

    companion object {
        const val EXTRA_PROFILE = "profile"
        private const val REQ_LOCAL_TREE = 9001
        private const val REQ_PACK_URL = 9002
        private const val REQ_LOCAL_PERMISSION = 9003
        private const val MAX_PACK_BYTES = 64 * 1024
        private const val MAX_PACK_REDIRECTS = 3
        private const val MAX_PACK_TOKENS = 96
        private val LANGUAGES = listOf(
            "hin" to "Hindi", "eng" to "English", "pan" to "Punjabi", "tam" to "Tamil",
            "tel" to "Telugu", "ben" to "Bengali", "mar" to "Marathi", "mal" to "Malayalam",
            "kan" to "Kannada", "guj" to "Gujarati", "urd" to "Urdu", "spa" to "Spanish",
            "fre" to "French", "ger" to "German", "jpn" to "Japanese", "kor" to "Korean",
        )
    }
}
