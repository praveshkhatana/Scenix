package com.scenix.tv

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.scenix.tv.core.AppContext
import com.scenix.tv.core.Motion
import com.scenix.tv.core.Focus
import com.scenix.tv.core.Views
import org.json.JSONArray
import org.json.JSONObject

/**
 * The household's people, managed from the sofa. This is the native answer to the web `#/manage`
 * page: it lists every profile with its kind, age and PIN state, and offers the same create, rename,
 * kind, age, language, PIN and remove operations.
 *
 * The hub owns every rule. A profile PIN is never compared here: a 403 that names the profile makes
 * this screen ask for its current PIN, and a 403 with `gate: 'profiles'` makes it ask for an adult's
 * PIN, exactly as the web editor does. Nothing in this file decides who may do what.
 */
class ManageProfilesActivity : Activity() {

    private lateinit var app: AppContext
    private lateinit var root: FrameLayout
    private lateinit var page: LinearLayout
    private lateinit var rows: LinearLayout
    private lateinit var status: TextView
    private val api by lazy { BrowseApi(applicationContext, (application as ScenixApp).prefs) }
    private var profiles: List<ProfileInfo> = emptyList()
    private var settings: JSONObject? = null
    /**
     * A separate window, not a view painted over the page.  The old faux sheet left focus in the
     * underlying profile list, so a D-pad press could select a parent profile through the dimmer.
     * A real dialog gives every picker its own focus scope and is the only safe model on a TV.
     */
    private var picker: Dialog? = null
    private var pinLayer: FrameLayout? = null
    private val pinBuf = StringBuilder()
    private var pinSub: TextView? = null
    private var pinDots: TextView? = null
    private var pinOnDone: ((String) -> Unit)? = null

    /** Digits the hub already accepted for a person, held only while this screen is open. */
    private val proofCache = HashMap<String, String>()
    private var draft: Draft? = null
    private var pendingRenameId: String? = null
    private var startAddRequested = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        app = (application as ScenixApp).ctx
        startAddRequested = intent?.getBooleanExtra(EXTRA_START_ADD, false) == true

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
        root.addView(page, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        setContentView(root)
        Motion.enter(app.theme, root)
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
        val title = Views.text(app, "li", 26f, app.theme.ink, "Profiles", bold = true)
        title.setPadding(app.theme.px(20f), 0, 0, 0)
        bar.addView(title)
        bar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        status = Views.text(app, "meta", 19f, app.theme.ink2, "", maxLines = 1)
        bar.addView(status)
        return bar
    }

    private fun load(message: String? = null) {
        status.text = "Reading the household…"
        api.profiles { list, error ->
            if (isFinishing || isDestroyed) return@profiles
            if (list == null) {
                status.text = error ?: "The hub is not answering"
                return@profiles
            }
            profiles = list
            // The ages and language names are the household's, not this screen's guess. A hub that
            // does not answer still leaves a usable list, so a settings failure is not fatal here.
            api.settings { sroot, _ ->
                if (isFinishing || isDestroyed) return@settings
                if (sroot != null) settings = sroot
                paint()
                page.visibility = View.VISIBLE
                status.text = message.orEmpty()
                // Do not make a viewer land on a profile row only to press again.  This is posted
                // after the real household has painted, so the editor owns focus rather than a
                // background row receiving the confirming D-pad press.
                if (startAddRequested) {
                    startAddRequested = false
                    rows.post { if (!isFinishing && !isDestroyed) beginAdd() }
                } else {
                    // The editor opens on a real profile action, never an invisible ScrollView
                    // or a distant back chevron. This also gives the first rendered frame a
                    // visible remote target.
                    rows.post { firstFocusable(rows)?.requestFocus() }
                }
                if (!message.isNullOrBlank()) {
                    rows.postDelayed({ if (!isFinishing && !isDestroyed) status.text = "" }, 2500)
                }
            }
        }
    }

    private fun paint() {
        rows.removeAllViews()
        // "People" read like a separate product area and the h1 token made it dominate the whole
        // screen. This is a household settings surface: say that plainly and reserve the giant
        // type for the profile gate's "Who's watching?" moment.
        rows.addView(Views.text(app, "h3", 36f, app.theme.ink, "Manage Profiles", bold = true),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        rows.addView(Views.text(app, "meta", 17f, app.theme.ink3,
            "Every person who can watch here. A PIN on a profile is checked by the hub.",
            maxLines = 2),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = app.theme.px(4f)
                bottomMargin = app.theme.px(18f)
            })
        for (p in profiles) addProfileRow(p)
        addRow("Add a profile", "New person", "Name, adult or kids, age and languages") { beginAdd() }
        rows.addView(Views.text(app, "meta", 16f, app.theme.ink3,
            "A locked profile's own PIN is needed to change its kind, age, languages or PIN. " +
                "Adding an adult profile, moving a child into the adult band, or removing a profile " +
                "without a PIN needs an adult profile's PIN.",
            maxLines = 4),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = app.theme.px(18f)
            })
    }

    private fun addProfileRow(p: ProfileInfo) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(app.theme.px(24f), 0, app.theme.px(24f), 0)
            background = Views.rounded(app.theme.surface, app.theme.px(10f))
            isFocusable = true
            isClickable = true
            setOnClickListener { showProfileActions(p) }
        }
        val words = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val avatar = ProfileAvatar.create(this, app, p.name, p.kids, p.avatar, api, app.theme.px(54f))
        row.addView(avatar, LinearLayout.LayoutParams(app.theme.px(54f), app.theme.px(54f)).apply {
            marginEnd = app.theme.px(18f)
        })
        words.addView(Views.text(app, "li", 24f, app.theme.ink, p.name, bold = true, maxLines = 1))
        words.addView(Views.text(app, "meta", 16f, app.theme.ink3, profileSummary(p), maxLines = 1),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = app.theme.px(2f)
            })
        row.addView(words, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(Views.text(app, "meta", 19f, app.theme.ink2, "Edit ›", maxLines = 1, gravity = Gravity.END))
        Focus.attach(row, row, app, app.theme.px(10f), scale = false, elevate = false)
        rows.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, app.theme.px(86f)).apply {
            bottomMargin = app.theme.px(10f)
        })
    }

    private fun profileSummary(p: ProfileInfo): String {
        val bits = ArrayList<String>()
        bits.add(if (p.kids) "Kids" else "Adult")
        if (p.age.isNotBlank()) bits.add("age ${p.age}")
        if (p.locked) bits.add("PIN set · locked")
        else if (p.pinSet) bits.add("PIN set · not asked")
        else bits.add("no PIN")
        if (p.languages.isNotEmpty()) bits.add(languageSummary(p.languages))
        return bits.joinToString(" · ")
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
        val words = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        words.addView(Views.text(app, "li", 24f, app.theme.ink, label, bold = true, maxLines = 1))
        if (note.isNotBlank()) {
            words.addView(Views.text(app, "meta", 16f, app.theme.ink3, note, maxLines = 1),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = app.theme.px(2f)
                })
        }
        row.addView(words, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(Views.text(app, "meta", 20f, app.theme.ink2, value, maxLines = 1, gravity = Gravity.END))
        Focus.attach(row, row, app, app.theme.px(10f), scale = false, elevate = false)
        rows.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, app.theme.px(86f)).apply {
            bottomMargin = app.theme.px(10f)
        })
    }

    // ---- one person ---------------------------------------------------------------------------

    private fun showProfileActions(p: ProfileInfo) {
        val options = ArrayList<Pair<String, String>>()
        options.add("avatar" to "Avatar — choose a picture")
        options.add("name" to "Rename — ${p.name}")
        options.add("kind" to "Kind — ${if (p.kids) "Kids" else "Adult"}")
        options.add("age" to "Age — ${p.age.ifBlank { "not set" }}")
        options.add("pin" to if (p.pinSet) "PIN — change or remove" else "PIN — set one")
        options.add("langs" to "Languages — ${languageSummary(p.languages)}")
        options.add("remove" to "Remove ${p.name}")
        showPicker("Edit ${p.name}", options, "") { action ->
            when (action) {
                "avatar" -> chooseAvatar(p)
                "name" -> rename(p)
                "kind" -> showKindPicker(p)
                "age" -> showAgePicker(p)
                "pin" -> showPinActions(p)
                "langs" -> showLanguageEditor(p)
                "remove" -> confirmRemove(p)
            }
        }
    }

    private fun chooseAvatar(p: ProfileInfo) {
        status.text = "Loading avatars…"
        api.avatarCharacters(p.id) { choices, error ->
            if (isFinishing || isDestroyed) return@avatarCharacters
            status.text = ""
            if (choices.isNullOrEmpty()) {
                status.text = if (error != null) "Avatar images are unavailable in this read-only preview" else
                    "No avatar images are cached on this hub yet"
                return@avatarCharacters
            }
            showAvatarPicker(p, choices)
        }
    }

    /** A remote-first character rail: choosing writes only a hub-local image reference. */
    private fun showAvatarPicker(p: ProfileInfo, choices: List<AvatarChoice>) {
        // Four evenly sized faces fit the same compact sheet as every other profile action.
        // Five overflowed the old 760px-only sheet and made the avatar choice feel like a web grid.
        val size = app.theme.px(104f)
        val gap = app.theme.px(12f)
        val grid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, app.theme.px(4f), 0, app.theme.px(4f))
        }
        choices.chunked(4).forEach { lineChoices ->
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            lineChoices.forEachIndexed { index, choice ->
                val tile = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    isFocusable = true
                    isClickable = true
                    contentDescription = "Use ${choice.label} as ${p.name}'s avatar"
                    setOnClickListener {
                        closePicker()
                        patchProfile(p, JSONObject().put("avatar", choice.image), "${p.name}'s avatar changed")
                    }
                }
                val face = ProfileAvatar.create(this, app, choice.label, false, choice.image, api, size)
                tile.addView(face, LinearLayout.LayoutParams(size, size))
                tile.addView(Views.text(app, "meta", 14f, app.theme.ink2, choice.label, gravity = Gravity.CENTER,
                    maxLines = 1), LinearLayout.LayoutParams(size + app.theme.px(8f), app.theme.px(28f)))
                Focus.attach(tile, face, app, size / 2, animateScale = false, elevate = false,
                    glowInset = app.theme.px(12f),
                    // The Apple pack's lift is elegant on posters but too subtle on a 104px face.
                    // A circular keyline makes the remote's current avatar unambiguous.
                    onFocus = { focused ->
                        face.foreground = if (focused) Views.ring(
                            Color.argb(238, 255, 255, 255), size / 2, app.theme.px(3f)
                        ) else null
                    })
                line.addView(tile, LinearLayout.LayoutParams(size + app.theme.px(8f), app.theme.px(148f)).apply {
                    if (index > 0) marginStart = gap
                })
            }
            grid.addView(line, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, app.theme.px(154f)))
        }
        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            isFocusable = false
            isFocusableInTouchMode = false
            addView(grid)
        }
        showPanel("Choose an avatar", scroll, app.theme.px(520f), app.theme.px(650f))
        val first = (grid.getChildAt(0) as? ViewGroup)?.getChildAt(0)
        first?.post { first.requestFocus() }
    }

    private fun rename(p: ProfileInfo) {
        pendingRenameId = p.id
        startActivityForResult(
            Intent(this, TextEntryActivity::class.java)
                .putExtra(TextEntryActivity.EXTRA_TITLE, "Rename ${p.name}")
                .putExtra(TextEntryActivity.EXTRA_VALUE, p.name)
                .putExtra(TextEntryActivity.EXTRA_HINT, "The name people see on the who's-watching screen")
                .putExtra(TextEntryActivity.EXTRA_PLAIN_TEXT, true),
            REQ_RENAME,
        )
    }

    private fun showKindPicker(p: ProfileInfo) {
        val current = if (p.kids) "kids" else "adult"
        showPicker("Kind for ${p.name}", listOf(
            "adult" to "Adult — the whole catalogue",
            "kids" to "Kids — the hub's own ceiling",
        ), current) { kind ->
            if (kind != current) {
                patchProfile(p, JSONObject().put("kind", kind),
                    if (kind == "kids") "${p.name} is a Kids profile" else "${p.name} is an Adult profile")
            }
        }
    }

    private fun showAgePicker(p: ProfileInfo) {
        val options = ArrayList<Pair<String, String>>()
        options.add("" to "No age — the hub's strictest band")
        for (age in ageOptions()) options.add(age.toString() to "Age $age")
        showPicker("Age for ${p.name}", options, p.age) { age ->
            val value: Any = if (age.isBlank()) JSONObject.NULL else age.toInt()
            patchProfile(p, JSONObject().put("age", value), "Age saved")
        }
    }

    private fun showPinActions(p: ProfileInfo) {
        val options = ArrayList<Pair<String, String>>()
        if (p.pinSet) {
            options.add("change" to "Change the PIN")
            options.add("remove" to "Remove the PIN")
        } else {
            options.add("set" to "Set a PIN")
        }
        showPicker("PIN for ${p.name}", options, "") { action ->
            when (action) {
                "set", "change" -> changePin(p)
                "remove" -> removePin(p)
            }
        }
    }

    private fun changePin(p: ProfileInfo) {
        // A profile with a stored PIN is asked for it first, so the viewer is not left choosing a new
        // number before being told the old one is needed. A stale cache still falls back to the hub's
        // 403 path inside mutate().
        ensureOwnProof(p, "change ${p.name}'s PIN") { _ ->
            closePinPad()
            askNewPin(p) { digits ->
                closePinPad()
                patchProfile(p, JSONObject().put("pin", digits).put("pinRequired", true),
                    "${p.name} now needs that PIN",
                    onSuccess = { proofCache[p.id] = digits })
            }
        }
    }

    private fun removePin(p: ProfileInfo) {
        ensureOwnProof(p, "remove ${p.name}'s PIN") { _ ->
            closePinPad()
            patchProfile(p, JSONObject().put("pin", JSONObject.NULL).put("pinRequired", false),
                "${p.name} no longer needs a PIN",
                onSuccess = { proofCache.remove(p.id) })
        }
    }

    private fun askNewPin(p: ProfileInfo, done: (String) -> Unit) {
        showPinPad(if (p.pinSet) "Choose a new PIN" else "Choose a PIN",
            "4 to 8 digits for ${p.name}") { entered ->
            if (entered.length !in 4..8) {
                pinError("Use 4 to 8 digits")
                return@showPinPad
            }
            done(entered)
        }
    }

    private fun confirmRemove(p: ProfileInfo) {
        showPicker("Remove ${p.name}?", listOf(
            "keep" to "Keep ${p.name}",
            "remove" to "Remove this profile",
        ), "keep") { action ->
            if (action != "remove") return@showPicker
            mutate("remove ${p.name}", p, JSONObject(), "${p.name} was removed",
                call = { body, done ->
                    api.deleteProfile(p.id, body) { r, c, e -> done(r, c, e) }
                },
                onSuccess = { proofCache.remove(p.id) })
        }
    }

    private fun showLanguageEditor(p: ProfileInfo) {
        showLanguageChooser("Languages for ${p.name}", p.languages) { chosen ->
            val arr = JSONArray()
            for (code in chosen) arr.put(code)
            patchProfile(p, JSONObject().put("languages", arr), "Languages saved")
        }
    }

    // ---- new person ---------------------------------------------------------------------------

    private fun beginAdd() {
        draft = null
        startActivityForResult(
            Intent(this, TextEntryActivity::class.java)
                .putExtra(TextEntryActivity.EXTRA_TITLE, "New profile")
                .putExtra(TextEntryActivity.EXTRA_HINT, "The name people see on the who's-watching screen")
                .putExtra(TextEntryActivity.EXTRA_PLAIN_TEXT, true),
            REQ_DRAFT_NAME,
        )
    }

    private fun showDraftEditor() {
        val d = draft ?: return
        val options = ArrayList<Pair<String, String>>()
        options.add("name" to "Name — ${d.name}")
        options.add("kind" to "Kind — ${kindLabel(d.kind)}")
        if (d.kind == "kids") options.add("age" to "Age — ${d.age ?: "not set"}")
        options.add("langs" to "Languages — ${languageSummary(d.languages)}")
        options.add("create" to "Create ${d.name}")
        options.add("cancel" to "Cancel")
        showPicker("New profile", options, "") { action ->
            when (action) {
                "name" -> startActivityForResult(
                    Intent(this, TextEntryActivity::class.java)
                        .putExtra(TextEntryActivity.EXTRA_TITLE, "New profile")
                        .putExtra(TextEntryActivity.EXTRA_VALUE, d.name)
                        .putExtra(TextEntryActivity.EXTRA_HINT, "The name people see on the who's-watching screen")
                        .putExtra(TextEntryActivity.EXTRA_PLAIN_TEXT, true),
                    REQ_DRAFT_NAME,
                )
                "kind" -> showDraftKindPicker()
                "age" -> showDraftAgePicker()
                "langs" -> showLanguageChooser("Languages for ${d.name}", d.languages) { chosen ->
                    d.languages.clear()
                    d.languages.addAll(chosen)
                    showDraftEditor()
                }
                "create" -> createDraft()
                "cancel" -> draft = null
            }
        }
    }

    private fun showDraftKindPicker() {
        val d = draft ?: return
        showPicker("Kind for ${d.name}", listOf(
            "adult" to "Adult — the whole catalogue",
            "kids" to "Kids — the hub's own ceiling",
        ), d.kind) { kind ->
            d.kind = kind
            if (kind != "kids") d.age = null
            showDraftEditor()
        }
    }

    private fun showDraftAgePicker() {
        val d = draft ?: return
        val options = ArrayList<Pair<String, String>>()
        options.add("" to "No age — the hub's strictest band")
        for (age in ageOptions()) options.add(age.toString() to "Age $age")
        showPicker("Age for ${d.name}", options, d.age ?: "") { age ->
            d.age = age.ifBlank { null }
            showDraftEditor()
        }
    }

    private fun createDraft() {
        val d = draft ?: return
        val body = JSONObject().put("name", d.name).put("kind", d.kind)
        if (d.age != null) body.put("age", d.age!!.toInt())
        if (d.languages.isNotEmpty()) {
            val arr = JSONArray()
            for (code in d.languages) arr.put(code)
            body.put("languages", arr)
        }
        mutate("add a profile", null, body, "${d.name} was added",
            call = { request, done ->
                api.createProfile(request) { r, c, e -> done(r, c, e) }
            },
            onSuccess = { draft = null })
    }

    private fun kindLabel(kind: String): String = if (kind == "kids") "Kids" else "Adult"

    // ---- hub calls and PIN proof ---------------------------------------------------------------

    private fun patchProfile(
        p: ProfileInfo,
        fields: JSONObject,
        note: String,
        onSuccess: (() -> Unit)? = null,
    ) {
        mutate("change ${p.name}", p, fields, note,
            call = { body, done ->
                api.updateProfile(p.id, body) { r, c, e -> done(r, c, e) }
            },
            onSuccess = onSuccess)
    }

    /**
     * Run one profile write and let the hub decide what proof it needs. The first attempt carries only
     * the proof already held for this visit. A 403 that names the profile asks for that person's PIN;
     * a 403 with `gate: 'profiles'` asks for an adult's PIN, because the act itself belongs to an adult.
     * The retry carries the digits as `pinCurrent`, and three refusals end the attempt rather than
     * turning the keypad into a loop.
     */
    private fun mutate(
        act: String,
        gateProfile: ProfileInfo?,
        base: JSONObject,
        note: String,
        call: (JSONObject, (JSONObject?, Int, String?) -> Unit) -> Unit,
        onSuccess: (() -> Unit)? = null,
        attempt: Int = 0,
        forcedPin: String? = null,
    ) {
        if (isFinishing || isDestroyed) return
        status.text = "Saving…"
        val body = JSONObject(base.toString())
        val proof = forcedPin ?: gateProfile?.let { proofCache[it.id] }
        if (!proof.isNullOrBlank()) body.put("pinCurrent", proof)
        call(body) answered@{ answer, code, error ->
            if (isFinishing || isDestroyed) return@answered
            if (code in 200..299) {
                onSuccess?.invoke()
                load(note)
                return@answered
            }
            val refusal = error ?: "The hub answered $code"
            if (code == 403 && attempt < 3) {
                if (answer?.optString("gate") == "profiles") {
                    val holder = adultHolder()
                    if (holder == null) {
                        closePinPad()
                        status.text = refusal
                        return@answered
                    }
                    status.text = ""
                    askRawPin("Adult PIN required", "An adult PIN is needed to $act — ${holder.name}") adult@{ entered ->
                        proofCache[holder.id] = entered
                        mutate(act, gateProfile, base, note, call, onSuccess, attempt + 1, entered)
                    }
                } else if (gateProfile != null) {
                    status.text = ""
                    askRawPin("${gateProfile.name}'s PIN", "Enter the current PIN to $act") own@{ entered ->
                        proofCache[gateProfile.id] = entered
                        mutate(act, gateProfile, base, note, call, onSuccess, attempt + 1, entered)
                    }
                } else {
                    closePinPad()
                    status.text = refusal
                }
            } else {
                closePinPad()
                status.text = refusal
            }
        }
    }

    /** The adult whose number opens an adult act: the hub's own order, adult profiles first. */
    private fun adultHolder(): ProfileInfo? {
        val withPin = profiles.filter { it.pinSet }
        return withPin.firstOrNull { !it.kids } ?: withPin.firstOrNull()
    }

    /**
     * Ask for the current PIN of a profile that has one and let the hub check it once, so a wrong
     * number is corrected on the keypad instead of after a second question.
     */
    private fun ensureOwnProof(p: ProfileInfo, act: String, onReady: (String) -> Unit) {
        if (!p.pinSet) {
            onReady("")
            return
        }
        val cached = proofCache[p.id]
        if (!cached.isNullOrBlank()) {
            onReady(cached)
            return
        }
        askRawPin("${p.name}'s PIN", "Enter the current PIN to $act") proof@{ entered ->
            api.unlock(p.id, entered) { ok, error ->
                if (isFinishing || isDestroyed) return@unlock
                if (ok) {
                    proofCache[p.id] = entered
                    closePinPad()
                    onReady(entered)
                } else {
                    val message = if (error == null || error.contains("wrong PIN", true)) {
                        "That PIN is not right"
                    } else {
                        error
                    }
                    pinError(message)
                }
            }
        }
    }

    // ---- overlays ------------------------------------------------------------------------------

    private fun showPicker(
        title: String,
        options: List<Pair<String, String>>,
        current: String,
        onPick: (String) -> Unit,
    ) {
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val rowViews = ArrayList<View>()
        for ((value, label) in options) {
            val selected = value == current
            val row = pickerRow(
                label = (if (selected) "✓   " else "") + label,
                selected = selected,
            ) {
                closePicker()
                onPick(value)
            }
            list.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, app.theme.px(60f)).apply {
                topMargin = app.theme.px(4f)
            })
            rowViews.add(row)
        }
        val listHeight = options.size * app.theme.px(64f)
        val contentHeight = listHeight.coerceAtMost(app.theme.px(560f))
        val content: View = if (listHeight > app.theme.px(560f)) {
            ScrollView(this).apply {
                isVerticalScrollBarEnabled = false
                isFocusable = false
                isFocusableInTouchMode = false
                addView(list)
            }
        } else list
        showPanel(title, content, contentHeight, contentHeight + app.theme.px(150f))
        val target = rowViews.getOrNull(options.indexOfFirst { it.first == current }.coerceAtLeast(0))
            ?: rowViews.firstOrNull()
        target?.post { target.requestFocus() }
    }

    /**
     * One checkbox per language, kept open while the viewer toggles. The hub's own names label the
     * codes; a code the profile already watches but the hub does not name is still offered, so a
     * language is never silently dropped by opening the list.
     */
    private fun showLanguageChooser(title: String, chosen: List<String>, onDone: (List<String>) -> Unit) {
        val picked = ArrayList(chosen)
        var focusIndex = 0
        fun render() {
            val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val rowViews = ArrayList<View>()
            val codes = ArrayList<String>()
            val labels = HashMap<String, String>()
            for ((code, label) in languageChoices()) {
                if (!codes.contains(code)) {
                    codes.add(code)
                    labels[code] = label
                }
            }
            for (code in picked) {
                if (!codes.contains(code)) {
                    codes.add(code)
                    labels[code] = code.uppercase()
                }
            }
            for ((index, code) in codes.withIndex()) {
                val selected = picked.contains(code)
                val row = pickerRow(
                    label = (if (selected) "✓   " else "") + (labels[code] ?: code),
                    selected = selected,
                ) {
                    focusIndex = index
                    if (selected) picked.remove(code) else picked.add(code)
                    render()
                }
                list.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, app.theme.px(60f)).apply {
                    topMargin = app.theme.px(4f)
                })
                rowViews.add(row)
            }
            val done = pickerRow("Done", selected = true) {
                closePicker()
                onDone(picked.toList())
            }
            list.addView(done, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, app.theme.px(60f)).apply {
                topMargin = app.theme.px(4f)
            })
            rowViews.add(done)

            val listHeight = (codes.size + 1) * app.theme.px(64f)
            val contentHeight = listHeight.coerceAtMost(app.theme.px(560f))
            val content: View = if (listHeight > app.theme.px(560f)) {
                ScrollView(this).apply {
                    isVerticalScrollBarEnabled = false
                    isFocusable = false
                    isFocusableInTouchMode = false
                    addView(list)
                }
            } else list
            showPanel(title, content, contentHeight, contentHeight + app.theme.px(150f))
            val target = rowViews.getOrNull(focusIndex.coerceIn(0, (rowViews.size - 1).coerceAtLeast(0)))
            target?.post { target.requestFocus() }
        }
        render()
    }

    /** A consistent, high-contrast remote-control row for every modal picker. */
    private fun pickerRow(label: String, selected: Boolean = false, onClick: () -> Unit): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(app.theme.px(18f), 0, app.theme.px(18f), 0)
            isFocusable = true
            isFocusableInTouchMode = false
            isClickable = true
            clipToOutline = true
        }
        val text = Views.text(app, "li", 22f, app.theme.ink, label, bold = selected, maxLines = 1)
        row.addView(text, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        fun paint(focused: Boolean) {
            // Apple TV's list focus is an unmistakable light plate, not a nearly invisible outline.
            // It remains inside the row's bounds, so adjacent choices never jump as focus moves.
            row.background = Views.rounded(
                when {
                    focused -> app.theme.ink
                    selected -> app.theme.surface2
                    else -> app.theme.color("panelRow", "#14161B")
                },
                app.theme.px(10f),
            )
            text.setTextColor(if (focused) app.theme.bg else app.theme.ink)
            text.typeface = if (focused || selected) android.graphics.Typeface.DEFAULT_BOLD
                else android.graphics.Typeface.DEFAULT
        }
        paint(false)
        row.onFocusChangeListener = View.OnFocusChangeListener { _, focused -> paint(focused) }
        row.setOnClickListener { onClick() }
        row.setOnKeyListener { _, code, event ->
            if (event.action == KeyEvent.ACTION_DOWN && code in setOf(
                    KeyEvent.KEYCODE_DPAD_CENTER,
                    KeyEvent.KEYCODE_ENTER,
                    KeyEvent.KEYCODE_NUMPAD_ENTER,
                    KeyEvent.KEYCODE_SPACE,
                )
            ) {
                row.performClick()
                true
            } else false
        }
        row.setOnHoverListener { view, event ->
            if (event.actionMasked == android.view.MotionEvent.ACTION_HOVER_ENTER) view.requestFocus()
            false
        }
        return row
    }

    private fun showPanel(title: String, content: View, contentHeight: Int, panelHeight: Int) {
        closePicker()
        val layer = FrameLayout(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            isClickable = true
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(app.theme.px(28f), app.theme.px(22f), app.theme.px(28f), app.theme.px(22f))
            background = Views.rounded(app.theme.color("panelScrim", "#E6000000"),
                app.theme.radius("modal", 12f),
                app.theme.px(1f), app.theme.line)
            elevation = app.theme.px(16f).toFloat()
        }
        panel.addView(Views.text(app, "li", 26f, app.theme.ink, title, bold = true, maxLines = 2),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = app.theme.px(10f)
            })
        panel.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, contentHeight))
        layer.addView(panel, FrameLayout.LayoutParams(app.theme.px(560f), panelHeight).apply {
            gravity = Gravity.CENTER
        })
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(layer)
        dialog.setCanceledOnTouchOutside(true)
        dialog.setOnKeyListener { _, code, event ->
            if (code == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_DOWN) {
                closePicker()
                true
            } else false
        }
        dialog.setOnDismissListener { if (picker === dialog) picker = null }
        picker = dialog
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            // Keep the page recognisable behind a sheet. A pack may tune this for its surface
            // language (glass console chrome needs less dim than a matte cinema panel), rather
            // than hard-coding one named theme into profile management.
            setDimAmount(app.theme.token("panel", "dimAmount", 0.78).toFloat().coerceIn(0f, 1f))
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }
    }

    private fun closePicker() {
        val active = picker
        picker = null
        active?.dismiss()
    }

    private fun firstFocusable(parent: ViewGroup): View? {
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            if (child.isFocusable && child.visibility == View.VISIBLE) return child
            if (child is ViewGroup) firstFocusable(child)?.let { return it }
        }
        return null
    }

    // ---- numeric pad ---------------------------------------------------------------------------

    private fun askRawPin(title: String, subtitle: String, onDone: (String) -> Unit) {
        showPinPad(title, subtitle, onDone)
    }

    private fun showPinPad(title: String, subtitle: String, onDone: (String) -> Unit) {
        closePinPad()
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
        panel.addView(pinSub, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = app.theme.px(8f)
        })
        pinDots = Views.text(app, "li", 32f, app.theme.ink, "○ ○ ○ ○", bold = true, gravity = Gravity.CENTER)
        panel.addView(pinDots, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
            app.theme.px(64f)).apply {
            topMargin = app.theme.px(16f)
        })
        val keys = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val keyRows = listOf(
            listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"),
            listOf("CLR", "0", "OK"),
        )
        for (labels in keyRows) {
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (label in labels) {
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
        panel.addView(keys, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT))
        layer.addView(panel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER
        })
        root.addView(layer, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        pinLayer = layer
        (keys.getChildAt(0) as? ViewGroup)?.getChildAt(0)?.post {
            (keys.getChildAt(0) as? ViewGroup)?.getChildAt(0)?.requestFocus()
        }
        renderPin()
    }

    private fun addPinDigit(d: Int) {
        if (pinBuf.length < 8) {
            pinBuf.append(d)
            renderPin()
        }
    }

    private fun backspacePin() {
        if (pinBuf.isNotEmpty()) {
            pinBuf.deleteCharAt(pinBuf.length - 1)
            renderPin()
        }
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
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (pinLayer != null) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_BACK -> { closePinPad(); return true }
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
        if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_DOWN && picker != null) {
            closePicker()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    @Deprecated("Activity result API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) {
            // Backing out of the draft's name field returns to the draft, not to a half-made person.
            if (requestCode == REQ_DRAFT_NAME && draft != null) showDraftEditor()
            return
        }
        val text = data?.getStringExtra(TextEntryActivity.EXTRA_RESULT).orEmpty().trim()
        when (requestCode) {
            REQ_RENAME -> {
                val id = pendingRenameId
                pendingRenameId = null
                val p = profiles.firstOrNull { it.id == id } ?: return
                if (text.isBlank() || text == p.name) return
                patchProfile(p, JSONObject().put("name", text), "Renamed to $text")
            }
            REQ_DRAFT_NAME -> {
                if (text.isBlank()) return
                val d = draft
                if (d == null) draft = Draft(text) else d.name = text
                showDraftEditor()
            }
        }
    }

    // ---- household choices ---------------------------------------------------------------------

    /** The ages `/app/v1/settings` offers, with a conservative band when the hub names none. */
    private fun ageOptions(): List<Int> {
        val out = ArrayList<Int>()
        val arr = settings?.optJSONObject("settings")
            ?.optJSONObject("profiles")
            ?.optJSONArray("ageOptions")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val age = arr.optInt(i, -1)
                if (age in 0..99 && !out.contains(age)) out.add(age)
            }
        }
        if (out.isEmpty()) out.addAll(FALLBACK_AGES)
        out.sort()
        return out
    }

    /** Language codes and labels, de-duplicated by the label the hub prints. */
    private fun languageChoices(): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        val names = settings?.optJSONObject("languageNames")
        if (names != null && names.length() > 0) {
            val seen = HashSet<String>()
            for (code in names.keys().asSequence().sortedBy { names.optString(it) }) {
                val label = names.optString(code).ifBlank { code }
                if (seen.add(label)) out.add(code to label)
            }
        }
        if (out.isEmpty()) out.addAll(FALLBACK_LANGUAGES)
        return out
    }

    private fun languageSummary(list: List<String>): String {
        if (list.isEmpty()) return "Household default"
        return list.joinToString(", ") { languageLabel(it) }
    }

    private fun languageLabel(code: String): String {
        for ((c, label) in languageChoices()) if (c == code) return label
        return code.uppercase()
    }

    private class Draft(var name: String) {
        var kind = "adult"
        var age: String? = null
        val languages = ArrayList<String>()
    }

    companion object {
        /** Launch straight into the new-person flow when the profile gate's plus tile was chosen. */
        const val EXTRA_START_ADD = "start_add"
        private const val REQ_RENAME = 71
        private const val REQ_DRAFT_NAME = 72

        private val FALLBACK_AGES = listOf(3, 5, 7, 9, 11, 13, 15, 17)
        private val FALLBACK_LANGUAGES = listOf(
            "hin" to "Hindi", "eng" to "English", "pan" to "Punjabi", "tam" to "Tamil",
            "tel" to "Telugu", "ben" to "Bengali", "mar" to "Marathi", "mal" to "Malayalam",
            "kan" to "Kannada", "guj" to "Gujarati", "urd" to "Urdu", "spa" to "Spanish",
            "fre" to "French", "ger" to "German", "jpn" to "Japanese", "kor" to "Korean",
        )
    }
}
