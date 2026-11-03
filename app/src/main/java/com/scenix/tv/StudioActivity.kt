package com.scenix.tv

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.scenix.tv.core.AppContext
import com.scenix.tv.core.Focus
import com.scenix.tv.core.Screens
import com.scenix.tv.core.Theme
import com.scenix.tv.core.Views
import org.json.JSONArray
import org.json.JSONObject

/** One catalog row, as `design-system/studio.json` declares it. */
data class StudioEntry(
    val id: String,
    val contract: String,
    val title: String,
    val kind: String,
    val builder: String,
    val states: List<String>,
    val themes: List<String>,
    val status: String,
) {
    val blocked: Boolean get() = status.startsWith("blocked")
}

/**
 * The Android component catalogue: the real views, grouped as a library.
 *
 * It is a debug screen, not a product screen. It exists so a component can be looked at, captured
 * and diffed in every state and theme without walking the whole app — and so a component that is
 * still buried inside an Activity is named as debt instead of being re-drawn.
 *
 * The manifest and fixtures are the same files the repository's gates read
 * (`design-system/studio.json`, `design-system/fixtures/studio.json`), copied into assets at build.
 */
class StudioActivity : Activity() {

    private lateinit var app: AppContext
    private var originalTheme: Theme? = null
    private var entries: List<StudioEntry> = emptyList()
    private var themeIds: List<Pair<String, String>> = emptyList()
    private var themeId = ""
    private var current: StudioEntry? = null
    private var currentState = ""
    private var capture = false

    private lateinit var root: FrameLayout
    private lateinit var stage: LinearLayout
    private lateinit var stageTitle: TextView
    private lateinit var stageMeta: TextView
    private lateinit var themeButton: TextView
    private var listColumn: LinearLayout? = null
    private var railPreview: com.scenix.tv.core.LeftRail? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if ((applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) == 0) {
            finish()
            return
        }
        app = (application as ScenixApp).ctx
        originalTheme = app.theme
        readCatalog()
        themeIds = readThemes()
        capture = intent?.getBooleanExtra(EXTRA_CAPTURE, false) == true
        val wantedTheme = intent?.getStringExtra(EXTRA_THEME).orEmpty()
        themeId = if (wantedTheme.isNotBlank()) wantedTheme else app.theme.id
        if (themeIds.none { it.first == themeId }) themeId = themeIds.firstOrNull()?.first ?: app.theme.id
        applyTheme(themeId)

        val wantedEntry = intent?.getStringExtra(EXTRA_ENTRY).orEmpty()
        current = entries.firstOrNull { it.id == wantedEntry } ?: entries.firstOrNull()
        currentState = intent?.getStringExtra(EXTRA_STATE).orEmpty()
            .ifBlank { current?.states?.firstOrNull().orEmpty() }

        if (capture) buildCapture() else buildShell()
    }

    override fun onDestroy() {
        // A catalogue that leaves the television on Kids after you looked at Kids is a bug.
        originalTheme?.let { if (!capture) app.theme = it }
        super.onDestroy()
    }

    // ---- catalog ------------------------------------------------------------------------

    private fun readCatalog() {
        entries = try {
            val text = assets.open("studio.json").bufferedReader().use { it.readText() }
            val arr = JSONObject(text).optJSONArray("entries") ?: JSONArray()
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                StudioEntry(
                    id = o.optString("id"),
                    contract = o.optString("contract"),
                    title = o.optString("title", o.optString("id")),
                    kind = o.optString("kind", "widget"),
                    builder = o.optString("builder"),
                    states = o.optJSONArray("states").let { a ->
                        if (a == null) emptyList() else (0 until a.length()).map { a.optString(it) }
                    },
                    themes = o.optJSONArray("themes").let { a ->
                        if (a == null) emptyList() else (0 until a.length()).map { a.optString(it) }
                    },
                    status = o.optString("status", "real"),
                )
            }
        } catch (t: Throwable) {
            emptyList()
        }
    }

    private fun readThemes(): List<Pair<String, String>> = try {
        val text = assets.open("themes.json").bufferedReader().use { it.readText() }
        val arr = JSONObject(text).optJSONArray("themes") ?: JSONArray()
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id")
            if (id.isBlank()) null else id to o.optString("name", id)
        }
    } catch (t: Throwable) {
        listOf(Theme.DEFAULT_ID to "Apple")
    }

    private fun applyTheme(id: String) {
        val asset = Theme.assetFor(assets, id)
        val dm = resources.displayMetrics
        app.theme = Theme.load(this, assets, dm.widthPixels, dm.heightPixels, asset, app.prefs.uiZoom())
        themeId = id
    }

    private fun themeName(): String = themeIds.firstOrNull { it.first == themeId }?.second ?: themeId

    // ---- shell --------------------------------------------------------------------------

    private fun buildShell() {
        val theme = app.theme
        root = FrameLayout(this).apply { setBackgroundColor(theme.bg) }
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(column, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))

        // header
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(theme.gutter, 0, theme.gutter, 0)
        }
        header.addView(Views.text(app, "h2", 34f, theme.ink, "Scenix Studio", bold = true))
        header.addView(Views.text(app, "meta", 16f, theme.ink3,
            "  the real components, in every state").apply { setPadding(theme.px(18f), theme.px(10f), 0, 0) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        header.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        themeButton = TextView(this).apply {
            text = "Theme · ${themeName()}"
            setTextColor(theme.ink)
            textSize = 18f
            setPadding(theme.px(18f), theme.px(12f), theme.px(18f), theme.px(12f))
            isFocusable = true
            isClickable = true
            background = Views.rounded(theme.surface2, theme.radius("button", 10f), theme.px(1f), theme.line)
            setOnClickListener { cycleTheme() }
        }
        Focus.attach(themeButton, themeButton, app, theme.radius("button", 10f), scale = false, elevate = false)
        header.addView(themeButton)
        column.addView(header, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, theme.px(96f)
        ))

        // body
        val body = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        column.addView(body, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        val listScroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        listScroll.addView(list)
        body.addView(listScroll, LinearLayout.LayoutParams(theme.px(430f), ViewGroup.LayoutParams.MATCH_PARENT))
        body.addView(View(this).apply { setBackgroundColor(theme.line) },
            LinearLayout.LayoutParams(theme.px(1f), ViewGroup.LayoutParams.MATCH_PARENT))

        val stageScroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        val stageColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(theme.gutter, theme.px(20f), theme.gutter, theme.px(32f))
        }
        stageTitle = Views.text(app, "h3", 30f, theme.ink, "", bold = true)
        stageMeta = Views.text(app, "meta", 16f, theme.ink3, "")
        stage = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        stageColumn.addView(stageTitle)
        stageColumn.addView(stageMeta, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = theme.px(4f) })
        stageColumn.addView(stage, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = theme.px(18f) })
        stageScroll.addView(stageColumn)
        body.addView(stageScroll, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))

        listColumn = list
        renderList()
        renderStage()
        setContentView(root)
    }

    private fun renderList() {
        val list = listColumn ?: return
        val theme = app.theme
        list.removeAllViews()
        for (entry in entries) {
            val selected = entry.id == current?.id
            val row = TextView(this).apply {
                text = buildString {
                    append(entry.title)
                    if (entry.blocked) append("  · blocked")
                }
                setTextColor(if (entry.blocked) theme.ink3 else theme.ink)
                textSize = 18f
                setPadding(theme.px(24f), theme.px(14f), theme.px(24f), theme.px(14f))
                isFocusable = true
                isClickable = true
                background = Views.rounded(
                    if (selected) theme.surface2 else Color.TRANSPARENT,
                    theme.radius("listItem", 10f),
                )
                setOnClickListener {
                    current = entry
                    currentState = entry.states.firstOrNull().orEmpty()
                    renderList()
                    renderStage()
                }
            }
            Focus.attach(row, row, app, theme.radius("listItem", 10f), scale = false, elevate = false)
            list.addView(row, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, theme.px(62f)
            ).apply { bottomMargin = theme.px(3f) })
        }
    }

    private fun cycleTheme() {
        val at = themeIds.indexOfFirst { it.first == themeId }.coerceAtLeast(0)
        val next = themeIds[(at + 1) % themeIds.size]
        val keepEntry = current?.id
        val keepState = currentState
        applyTheme(next.first)
        themeButton.text = "Theme · ${themeName()}"
        current = entries.firstOrNull { it.id == keepEntry }
        currentState = keepState
        renderList()
        renderStage()
    }

    private fun renderStage() {
        val entry = current
        val theme = app.theme
        stage.removeAllViews()
        stageMeta.text = if (entry == null) "studio.json is missing or empty" else
            "${entry.id} · ${entry.contract} · ${entry.status}"
        if (entry == null) {
            stageTitle.text = "No catalog"
            return
        }
        stageTitle.text = entry.title
        if (entry.blocked) {
            val panel = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(theme.px(28f), theme.px(24f), theme.px(28f), theme.px(24f))
                background = Views.rounded(theme.surface, theme.radius("panel", 14f), theme.px(1f), theme.line)
            }
            panel.addView(Views.text(app, "li", 22f, theme.ink, "Not extracted yet", bold = true))
            panel.addView(Views.text(app, "sub", 18f, theme.ink2,
                "This component still lives inside an Activity. The catalogue will not draw a second " +
                    "version of it; the parity gate keeps it red until it is extracted.", maxLines = 3))
            stage.addView(panel)
            return
        }

        // state pills
        if (entry.states.isNotEmpty()) {
            val pills = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            for (state in entry.states) {
                val pill = TextView(this).apply {
                    text = state
                    setTextColor(if (state == currentState) theme.bg else theme.ink2)
                    textSize = 16f
                    setPadding(theme.px(16f), theme.px(8f), theme.px(16f), theme.px(8f))
                    isFocusable = true
                    isClickable = true
                    background = Views.rounded(
                        if (state == currentState) theme.ink else theme.surface2,
                        theme.px(999f),
                    )
                    setOnClickListener {
                        currentState = state
                        renderStage()
                    }
                }
                Focus.attach(pill, pill, app, theme.px(999f), scale = false, elevate = false)
                pills.addView(pill, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = theme.px(8f) })
            }
            stage.addView(pills, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = theme.px(16f) })
        }

        val view = StudioCatalog.build(this, app, entry, currentState)
        if (view != null) {
            stage.addView(view, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            return
        }

        if (entry.builder == "nav.rail.left") {
            stage.addView(actionRow("Open the left rail", "The real LeftRail, over this page.") {
                openRailPreview(currentState)
            })
            return
        }
        if (entry.builder == "sheet.action") {
            stage.addView(actionRow("Open the hold sheet", "It is the real CardActionSheet, over this page.") {
                StudioCatalog.openHoldSheet(this, app)
            })
            return
        }
        if (entry.kind == "screen" || entry.builder == "player.preview") {
            stage.addView(actionRow("Open the real player chrome", "The PlayerActivity preview, not a drawing.") {
                StudioCatalog.launchPlayerPreview(this, currentState, themeId)
            })
            return
        }
        stage.addView(Views.text(app, "sub", 18f, theme.ink2, "No builder wired for ${entry.builder}."))
    }

    private fun actionRow(label: String, note: String, run: () -> Unit): View {
        val theme = app.theme
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(theme.px(26f), theme.px(20f), theme.px(26f), theme.px(20f))
            background = Views.rounded(theme.surface, theme.radius("panel", 14f), theme.px(1f), theme.line)
        }
        val action = TextView(this).apply {
            text = label
            setTextColor(theme.bg)
            textSize = 20f
            setPadding(theme.px(22f), theme.px(12f), theme.px(22f), theme.px(12f))
            isFocusable = true
            isClickable = true
            background = Views.rounded(theme.ink, theme.px(999f))
            setOnClickListener { run() }
        }
        Focus.attach(action, action, app, theme.px(999f), scale = false, elevate = false)
        box.addView(action, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        box.addView(Views.text(app, "meta", 16f, theme.ink3, note, maxLines = 2),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = theme.px(10f) })
        return box
    }

    // ---- capture ------------------------------------------------------------------------

    private fun buildCapture() {
        val theme = app.theme
        root = FrameLayout(this).apply { setBackgroundColor(theme.bg) }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(theme.px(24f), theme.px(18f), theme.px(24f), theme.px(18f))
            setBackgroundColor(theme.bg)
        }
        stage = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(stage, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        root.addView(column, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        setContentView(root)
        val entry = current
        if (entry == null) return
        if (entry.kind == "screen" || entry.builder == "player.preview") {
            stage.postDelayed({ StudioCatalog.launchPlayerPreview(this, currentState, themeId) }, 250L)
            return
        }
        if (entry.builder == "nav.rail.left") {
            stage.postDelayed({ openRailPreview(currentState) }, 250L)
            return
        }
        if (entry.builder == "sheet.action") {
            stage.postDelayed({ StudioCatalog.openHoldSheet(this, app) }, 250L)
            return
        }
        StudioCatalog.build(this, app, entry, currentState)?.let {
            stage.addView(it, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            // A widget with focusable children grabs the first one when the window opens, so an
            // "idle" golden could silently record a focused control. Only a state that is about
            // focus may take it.
            if (currentState != "focused") {
                stage.post {
                    root.findFocus()?.clearFocus()
                    root.isFocusable = true
                    root.isFocusableInTouchMode = true
                    root.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                    root.requestFocus()
                }
            }
        }
    }

    private fun openRailPreview(state: String) {
        railPreview?.let { if (it.parent === root) root.removeView(it) }
        val rail = StudioCatalog.leftRail(this, app)
        railPreview = rail
        val lp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        )
        root.addView(rail, lp)
        rail.open(if (state == "focused") "games" else "home")
    }

    companion object {
        const val EXTRA_CAPTURE = "capture"
        const val EXTRA_ENTRY = "entry"
        const val EXTRA_STATE = "state"
        const val EXTRA_THEME = "theme"
    }
}
