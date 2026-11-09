package com.scenix.tv.plugins.nav

import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import com.scenix.tv.R
import com.scenix.tv.core.AppContext
import com.scenix.tv.core.Focus
import com.scenix.tv.core.HubAddress
import com.scenix.tv.core.HubDiscovery
import com.scenix.tv.core.Prefs
import com.scenix.tv.core.ScreenHost
import com.scenix.tv.core.Views

/**
 * The front door of a TV that has no backend yet.
 *
 * Nothing is guessed here. A fresh install names no machine, so the app would otherwise sit on an
 * empty network showing nothing, or worse, showing the bundled sample rows as though they were this
 * household's library. This screen states the two ways Scenix runs and lets the viewer pick one:
 *
 *  - a hub on this network, found by the LAN announcement ([HubDiscovery]) and listed by the name its
 *    owner gave it, or typed in by hand for a hub that is not on this network at all;
 *  - standalone, where this app is the backend and pulls its own content from addons.
 *
 * Once a choice is made the watcher is told to look again (`ctx.watch?.rechoose()`), so the new
 * backend is live immediately, and the app opens the web shell — the only product after setup.
 */
object PairingScreen {
    const val id = "pairing"

    /** One line of feedback on the field: what was wrong with the address just typed. */
    private var addressError: String? = null

    /** Clear the field error when the screen is entered again, not when it repaints around it. */
    fun reset() { addressError = null }

    fun render(host: ScreenHost) {
        val ctx = host.app
        val theme = ctx.theme
        val container = host.container()

        val root = LinearLayout(ctx.app)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(theme.bg)
        container.addView(
            root,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )

        val scroll = ScrollView(ctx.app).apply {
            isFillViewport = true
            clipToPadding = false
        }
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val col = LinearLayout(ctx.app)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(theme.gutter, theme.px(30f), theme.gutter, theme.px(64f))
        scroll.addView(col)

        addEyebrow(ctx, col, "DEVICE SETUP")
        col.addView(
            Views.text(ctx, "h2", 48f, theme.ink, "Choose how this TV streams", bold = true),
            lp(0),
        )
        col.addView(
            Views.text(
                ctx, "sub", 22f, theme.ink2,
                "Use a Hub for a shared, always-on library, or keep this TV independent with its own sources.",
                maxLines = 2,
            ),
            lp(theme.px(7f)),
        )

        // A paired TV that reopens setup needs to know what it is currently using before it can
        // decide whether to replace it.
        val backend = ctx.prefs.backendChoice()
        val currentHub = ctx.prefs.hubUrl(ctx.app.getString(R.string.hub_url))
        val currentName = ctx.prefs.discoveredHubs().firstOrNull { it.url == currentHub }?.name.orEmpty()
        val current = when {
            backend == Prefs.BACKEND_STANDALONE -> "Currently: this TV is the backend (standalone)."
            backend == Prefs.BACKEND_HUB && currentName.isNotBlank() -> "Currently connected to $currentName."
            backend == Prefs.BACKEND_HUB -> "Currently connected to $currentHub."
            else -> ""
        }
        if (current.isNotBlank()) connectionPanel(ctx, col, current)

        // ---- hubs announcing themselves -------------------------------------------------------
        section(ctx, col, "HUB MODE")
        infoRow(
            ctx,
            col,
            "A Hub keeps profiles, source health and TV settings in one place. It is the recommended choice for a shared home.",
        )
        if (HubDiscovery.isScanning()) {
            infoRow(ctx, col, "Looking for Hubs on this network\u2026")
        } else {
            val found = ctx.prefs.discoveredHubs()
            if (found.isEmpty()) {
                quietState(
                    ctx,
                    col,
                    "No Hub discovered yet",
                    "Start your Hub, then search again. You can also connect with an address below.",
                )
            }
            for (hub in found) hubRow(ctx, col, hub, host)
        }
        actionRow(ctx, col, "Find Hubs on this network", "Discovery only — no connection is made", "SEARCH") { scan(host) }

        // ---- or an address nobody announced ---------------------------------------------------
        section(ctx, col, "CONNECT MANUALLY")
        addressField(ctx, col, host)

        // ---- or no hub at all -----------------------------------------------------------------
        section(ctx, col, "STANDALONE MODE")
        standaloneCard(ctx, col, host)
        infoRow(
            ctx, col,
            "Standalone plays direct HTTP sources managed on this TV. It does not provide a shared browser control centre, P2P copies or Live TV.",
        )

        // ---- installing a hub, for someone who has never had one ------------------------------
        section(ctx, col, "NEW TO HUBS?")
        infoRow(
            ctx,
            col,
            "Run the Hub on a computer that can stay on, then open its web control centre to manage profiles and sources. Return here to search or enter its address.",
        )

        // A screen that opens onto an empty list is a screen that looks broken, so the first render
        // asks the network once. The watcher has usually already done this pass; isScanning keeps the
        // two from broadcasting at the same time.
        if (!HubDiscovery.isScanning() && ctx.prefs.discoveredHubs().isEmpty()) scan(host, silent = true)
    }

    // ---- what the choices do ----------------------------------------------------------------

    /**
     * One hub, however it was found: remembered as the address to try first next time, its name kept
     * so the list says something a person wrote rather than a host, and the watcher told to look now.
     */
    private fun chooseHub(host: ScreenHost, url: String, name: String) {
        val ctx = host.app
        ctx.prefs.setHubUrl(url)
        ctx.prefs.setHubName(url, name)
        ctx.prefs.setBackendChoice(Prefs.BACKEND_HUB)
        // Same rule as the launch switch: naming a hub means "show me the real library", so the
        // bundled-sample pin cannot survive it.
        ctx.prefs.setFixture("")
        ctx.watch?.rechoose()
        host.openShell()
    }

    /** Standalone: this app hosts the contract itself, so there is no reachability question to ask. */
    private fun goStandalone(host: ScreenHost) {
        val ctx = host.app
        ctx.prefs.setBackendChoice(Prefs.BACKEND_STANDALONE)
        ctx.prefs.setFixture("")
        ctx.watch?.rechoose()
        host.openShell()
    }

    private fun scan(host: ScreenHost, silent: Boolean = false) {
        val ctx = host.app
        // A second search while one is in flight answers at once with nothing, which would read to the
        // viewer as "no hub" — so the one in flight is left to finish.
        if (HubDiscovery.isScanning()) return
        HubDiscovery.scan(
            ctx.app,
            ctx.app.getString(R.string.ssdp_search_target),
            ctx.app.resources.getInteger(R.integer.ssdp_search_ms),
        ) { found ->
            val known = LinkedHashMap(ctx.prefs.discoveredHubs().associateBy { it.url })
            for (f in found) known[f.url] = f
            ctx.prefs.setDiscoveredHubs(ArrayList(known.values))
            // The answer arrives on the UI thread; repaint in place so the stack does not grow every
            // time the remote lands on Search.
            if (host.currentScreenId() == id) host.refresh()
            if (!silent) host.toast(if (found.isEmpty()) "No hub answered" else "Found ${found.size} hub(s)")
        }
        if (host.currentScreenId() == id) host.refresh()
    }

    // ---- small builders -----------------------------------------------------------------------

    private fun hubRow(ctx: AppContext, col: LinearLayout, hub: HubAddress, host: ScreenHost) {
        val theme = ctx.theme
        val radius = theme.radius("panel", 18f)
        val card = LinearLayout(ctx.app).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(theme.px(28f), theme.px(20f), theme.px(28f), theme.px(18f))
            background = panelFill(ctx, radius)
            isFocusable = true
            isClickable = true
            contentDescription = "${hub.display()}. Discovered Hub at ${hub.url}. Press Select to connect."
        }
        val top = LinearLayout(ctx.app).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(
            Views.text(ctx, "li", 26f, theme.ink, hub.display(), bold = true, maxLines = 1),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        val discovered = Views.text(ctx, "meta", 15f, theme.brand, "DISCOVERED", bold = true)
        discovered.letterSpacing = 0.12f
        discovered.setPadding(theme.px(13f), theme.px(7f), theme.px(13f), theme.px(7f))
        discovered.background = Views.rounded(theme.colorOr("chipBg", theme.surface2), theme.radius("chip", 12f))
        top.addView(discovered)
        card.addView(top)
        card.addView(
            Views.text(ctx, "liMeta", 20f, theme.ink2, hub.url, maxLines = 1),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = theme.px(8f)
            },
        )
        val action = Views.text(ctx, "meta", 17f, theme.brand, "CONNECT THIS TV  ›", bold = true)
        action.letterSpacing = 0.08f
        card.addView(
            action,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = theme.px(17f)
            },
        )
        Focus.attach(
            card,
            card,
            ctx,
            radius,
            scale = false,
            animateScale = false,
            elevate = false,
            glowInset = theme.focusGlowInsetSm,
            control = true,
            onClick = { chooseHub(host, hub.url, hub.name) },
        )
        col.addView(card, lp(theme.px(10f)))
    }

    /** The typed-address case: a hub on another network, or one that has not been told to announce. */
    private fun addressField(ctx: AppContext, col: LinearLayout, host: ScreenHost) {
        val theme = ctx.theme
        val radius = theme.radius("panel", 18f)
        val box = LinearLayout(ctx.app)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(theme.px(28f), theme.px(22f), theme.px(28f), theme.px(24f))
        box.background = panelFill(ctx, radius)
        col.addView(box, lp(theme.px(10f)))

        val overline = Views.text(ctx, "meta", 15f, theme.brand, "HUB ADDRESS", bold = true)
        overline.letterSpacing = 0.12f
        box.addView(overline)
        box.addView(
            Views.text(ctx, "li", 27f, theme.ink, "Connect a Hub by address", bold = true),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = theme.px(6f)
            },
        )
        box.addView(
            Views.text(ctx, "liMeta", 19f, theme.ink2, "Use this when the Hub is on another network or is not announcing itself.", maxLines = 2),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = theme.px(5f)
            },
        )
        box.addView(
            Views.text(ctx, "li", 19f, theme.ink2, "Hub address"),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = theme.px(20f)
            },
        )

        val field = EditText(ctx.app)
        field.hint = "http://<hub-host>:8788"
        field.setText(ctx.prefs.hubUrl(ctx.app.getString(R.string.hub_url)))
        field.setTextColor(theme.ink)
        field.setHintTextColor(theme.ink3)
        field.setTextSize(TypedValue.COMPLEX_UNIT_PX, theme.type("li", 22f))
        field.background = Views.rounded(theme.surface2, theme.radius("field", 8f), theme.px(1f), theme.line)
        field.setPadding(theme.px(18f), theme.px(13f), theme.px(18f), theme.px(13f))
        field.minHeight = theme.px(58f)
        field.setSingleLine(true)
        val inputType = android.text.InputType.TYPE_CLASS_TEXT or
            android.text.InputType.TYPE_TEXT_VARIATION_URI or
            android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        field.inputType = inputType
        val flp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        flp.topMargin = theme.px(9f)
        box.addView(field, flp)
        Focus.attach(
            field,
            field,
            ctx,
            theme.radius("field", 8f),
            scale = false,
            animateScale = false,
            elevate = false,
            control = true,
        )

        var current = field.text?.toString() ?: ""
        field.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { current = s?.toString() ?: "" }
        })

        // Optional. A hub with HUB_TOKEN set answers Basic auth; the TV supplies the household token
        // through the WebView's auth handler, so the page never sees it. Blank means the old open-LAN
        // behaviour, and the field stays out of the way.
        box.addView(
            Views.text(ctx, "li", 19f, theme.ink2, "Access token (only if your Hub asks)"),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = theme.px(12f)
            },
        )
        val token = EditText(ctx.app)
        token.hint = "optional"
        token.setText(ctx.prefs.hubToken())
        token.setTextColor(theme.ink)
        token.setHintTextColor(theme.ink3)
        token.setTextSize(TypedValue.COMPLEX_UNIT_PX, theme.type("li", 21f))
        token.background = Views.rounded(theme.surface2, theme.radius("field", 8f), theme.px(1f), theme.line)
        token.setPadding(theme.px(18f), theme.px(12f), theme.px(18f), theme.px(12f))
        token.minHeight = theme.px(54f)
        token.setSingleLine(true)
        token.inputType = android.text.InputType.TYPE_CLASS_TEXT or
            android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD or
            android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        token.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { ctx.prefs.setHubToken(s?.toString() ?: "") }
        })
        val tlp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        tlp.topMargin = theme.px(8f)
        box.addView(token, tlp)
        Focus.attach(
            token,
            token,
            ctx,
            theme.radius("field", 8f),
            scale = false,
            animateScale = false,
            elevate = false,
            control = true,
        )

        val row = LinearLayout(ctx.app)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        val slp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        slp.topMargin = theme.px(14f)

        val save = Views.text(ctx, "button", 20f, theme.onBrand, "CONNECT HUB", bold = true, gravity = Gravity.CENTER)
        save.setPadding(theme.px(24f), theme.px(12f), theme.px(24f), theme.px(12f))
        save.background = Views.rounded(theme.brand, theme.radius("button", 8f))
        save.isFocusable = true
        save.setOnClickListener {
            val url = HubDiscovery.normalise(current)
            if (url == null) {
                addressError = "That is not an address. Try the form http://<hub-host>:8788"
                if (host.currentScreenId() == id) host.refresh()
            } else {
                addressError = null
                chooseHub(host, url, "")
            }
        }
        Focus.attach(
            save,
            save,
            ctx,
            theme.radius("button", 8f),
            scale = false,
            animateScale = false,
            elevate = false,
            control = true,
        )
        row.addView(save, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        if (addressError != null) {
            val err = Views.text(ctx, "liMeta", 21f, theme.color("warn", "#F2C33A"), addressError ?: "", maxLines = 2)
            val elp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            elp.marginStart = theme.px(24f)
            row.addView(err, elp)
        }
        box.addView(row, slp)
    }

    // ---- setup surface ------------------------------------------------------------------------

    private fun addEyebrow(ctx: AppContext, col: LinearLayout, label: String) {
        val eyebrow = Views.text(ctx, "meta", 16f, ctx.theme.brand, label, bold = true)
        eyebrow.letterSpacing = 0.16f
        col.addView(eyebrow, lp(0))
    }

    private fun panelFill(ctx: AppContext, radius: Int) = Views.rounded(
        ctx.theme.colorOr("settingsPanel", ctx.theme.surface),
        radius,
        maxOf(1, ctx.theme.px(1f)),
        ctx.theme.colorOr("settingsPanelStroke", ctx.theme.line),
    )

    private fun connectionPanel(ctx: AppContext, col: LinearLayout, value: String) {
        val theme = ctx.theme
        val radius = theme.radius("panel", 18f)
        val panel = LinearLayout(ctx.app).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(theme.px(28f), theme.px(18f), theme.px(28f), theme.px(18f))
            background = panelFill(ctx, radius)
        }
        val label = Views.text(ctx, "meta", 15f, theme.brand, "CURRENT CONNECTION", bold = true)
        label.letterSpacing = 0.13f
        panel.addView(label)
        panel.addView(
            Views.text(ctx, "li", 23f, theme.ink, value, bold = true, maxLines = 2),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = theme.px(6f)
            },
        )
        col.addView(panel, lp(theme.px(18f)))
    }

    private fun quietState(ctx: AppContext, col: LinearLayout, title: String, detail: String) {
        val theme = ctx.theme
        val panel = LinearLayout(ctx.app).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(theme.px(28f), theme.px(18f), theme.px(28f), theme.px(18f))
            background = Views.rounded(
                theme.colorOr("chipBg", theme.surface2),
                theme.radius("panel", 18f),
                maxOf(1, theme.px(1f)),
                theme.line,
            )
            contentDescription = "$title. $detail"
        }
        panel.addView(Views.text(ctx, "li", 23f, theme.ink, title, bold = true))
        panel.addView(
            Views.text(ctx, "liMeta", 19f, theme.ink2, detail, maxLines = 3),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = theme.px(6f)
            },
        )
        col.addView(panel, lp(theme.px(10f)))
    }

    private fun standaloneCard(ctx: AppContext, col: LinearLayout, host: ScreenHost) {
        val theme = ctx.theme
        val radius = theme.radius("panel", 18f)
        val card = LinearLayout(ctx.app).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(theme.px(28f), theme.px(22f), theme.px(28f), theme.px(20f))
            background = panelFill(ctx, radius)
            isFocusable = true
            isClickable = true
            contentDescription = "Run on this TV. Standalone mode. Press Select to start standalone mode."
        }
        val label = Views.text(ctx, "meta", 15f, theme.brand, "RUN ON THIS TV", bold = true)
        label.letterSpacing = 0.13f
        card.addView(label)
        card.addView(
            Views.text(ctx, "li", 28f, theme.ink, "Keep this TV independent", bold = true),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = theme.px(7f)
            },
        )
        card.addView(
            Views.text(ctx, "liMeta", 19f, theme.ink2, "Add sources here and watch directly from this television — no second machine required.", maxLines = 2),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = theme.px(6f)
            },
        )
        val action = Views.text(ctx, "meta", 17f, theme.brand, "START STANDALONE  ›", bold = true)
        action.letterSpacing = 0.08f
        card.addView(
            action,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = theme.px(18f)
            },
        )
        Focus.attach(
            card,
            card,
            ctx,
            radius,
            scale = false,
            animateScale = false,
            elevate = false,
            glowInset = theme.focusGlowInsetSm,
            control = true,
            onClick = { goStandalone(host) },
        )
        col.addView(card, lp(theme.px(10f)))
    }

    private fun section(ctx: AppContext, col: LinearLayout, label: String) {
        val title = Views.text(ctx, "rowTitle", 21f, ctx.theme.ink3, label, bold = true)
        title.letterSpacing = 0.14f
        col.addView(title, lp(ctx.theme.px(30f)))
    }

    private fun infoRow(ctx: AppContext, col: LinearLayout, value: String) {
        val theme = ctx.theme
        val row = LinearLayout(ctx.app).apply {
            setPadding(theme.px(28f), theme.px(16f), theme.px(28f), theme.px(16f))
            background = Views.rounded(
                theme.colorOr("chipBg", theme.surface2),
                theme.radius("panel", 18f),
                maxOf(1, theme.px(1f)),
                theme.line,
            )
        }
        row.addView(Views.text(ctx, "liMeta", 19f, theme.ink2, value, maxLines = 4))
        col.addView(row, lp(theme.px(10f)))
    }

    private fun actionRow(
        ctx: AppContext,
        col: LinearLayout,
        label: String,
        value: String,
        action: String,
        onClick: () -> Unit,
    ) {
        val theme = ctx.theme
        val radius = theme.radius("panel", 18f)
        val row = LinearLayout(ctx.app).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(theme.px(28f), theme.px(18f), theme.px(28f), theme.px(18f))
            background = panelFill(ctx, radius)
            isFocusable = true
            isClickable = true
            contentDescription = "$label. $value. Press Select to $action."
        }
        val copy = LinearLayout(ctx.app).apply { orientation = LinearLayout.VERTICAL }
        copy.addView(Views.text(ctx, "li", 24f, theme.ink, label, bold = true))
        if (value.isNotBlank()) {
            copy.addView(
                Views.text(ctx, "liMeta", 18f, theme.ink2, value, maxLines = 2),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = theme.px(5f)
                },
            )
        }
        row.addView(copy, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val actionView = Views.text(ctx, "meta", 16f, theme.brand, action, bold = true, gravity = Gravity.END)
        actionView.letterSpacing = 0.08f
        row.addView(
            actionView,
            marginLp(theme.px(24f)),
        )
        Focus.attach(
            row,
            row,
            ctx,
            radius,
            scale = false,
            animateScale = false,
            elevate = false,
            glowInset = theme.focusGlowInsetSm,
            control = true,
            onClick = onClick,
        )
        col.addView(row, lp(theme.px(10f)))
    }

    /** Two lines stacked inside a horizontal row: the name, and the address under it. */
    private fun col2(ctx: AppContext, parent: LinearLayout, a: View, b: View) {
        val stack = LinearLayout(ctx.app)
        stack.orientation = LinearLayout.VERTICAL
        stack.addView(a, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val blp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        blp.topMargin = ctx.theme.px(4f)
        stack.addView(b, blp)
        parent.addView(stack, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun marginLp(ml: Int): LinearLayout.LayoutParams {
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.marginStart = ml
        return lp
    }

    private fun lp(top: Int): LinearLayout.LayoutParams {
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = top
        return lp
    }
}
