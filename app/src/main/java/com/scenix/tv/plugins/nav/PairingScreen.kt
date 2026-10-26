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
import com.scenix.tv.core.HttpHub
import com.scenix.tv.core.LocalHub
import com.scenix.tv.core.ScreenHost
import com.scenix.tv.core.ScreenPlugin
import com.scenix.tv.core.Views

/**
 * The front door of a TV that has no backend yet (design/deployment-modes.md Rule 4).
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
 * backend is live immediately rather than at the next scheduled probe, and the app moves to the
 * profile picker — the same front door every other path leads to.
 */
object PairingScreen : ScreenPlugin {
    override val id get() = "pairing"
    override val title get() = "Set up"
    override val route get() = "pairing"
    override val showInNav = false
    override val order = 600

    /** One line of feedback on the field: what was wrong with the address just typed. */
    private var addressError: String? = null

    override fun render(host: ScreenHost) {
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

        val scroll = ScrollView(ctx.app)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val col = LinearLayout(ctx.app)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(theme.gutter, theme.px(48f), theme.gutter, theme.px(72f))
        scroll.addView(col)

        col.addView(
            Views.text(ctx, "h2", 52f, theme.ink, "Set up this TV", bold = true),
            lp(0),
        )
        col.addView(
            Views.text(
                ctx, "sub", 24f, theme.ink2,
                "Scenix streams from a hub: a small server on a machine you already own. " +
                    "A TV with no hub can still run on its own, pulling its own content from addons.",
                maxLines = 3,
            ),
            lp(theme.px(12f)),
        )

        // ---- hubs announcing themselves -------------------------------------------------------
        section(ctx, col, "Hubs on this network")
        if (HubDiscovery.isScanning()) {
            infoRow(ctx, col, "Looking for hubs on this network\u2026")
        } else {
            val found = ctx.prefs.discoveredHubs()
            if (found.isEmpty()) {
                infoRow(ctx, col, "Nothing is announcing itself. A hub answers a search on its own, so start the hub and search again.")
            }
            for (hub in found) hubRow(ctx, col, hub, host)
        }
        actionRow(ctx, col, "Search again", "", "Run") { scan(host) }

        // ---- or an address nobody announced ---------------------------------------------------
        section(ctx, col, "Or type an address")
        addressField(ctx, col, host)

        // ---- or no hub at all -----------------------------------------------------------------
        section(ctx, col, "Or no hub")
        actionRow(ctx, col, "Keep this TV on its own", "this app becomes the backend", "Start") {
            goStandalone(host)
        }
        infoRow(
            ctx, col,
            "On its own this TV plays direct HTTP links \u2014 no torrents, no live TV. What it can show comes from addons you add in Settings.",
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
        ctx.prefs.setBackendChoice("hub")
        ctx.prefs.setStandalone(false)
        // Same rule as the launch switch: naming a hub means "show me the real library", so the
        // bundled-sample pin cannot survive it.
        ctx.prefs.setFixture("")
        ctx.hub = HttpHub(ctx.app, ctx.prefs)
        ctx.offline = false
        ctx.capabilities = null
        ctx.watch?.rechoose { online -> BrowseHelper.runOnUiThread { host.toast(if (online) "Streaming from the hub" else "That hub is not answering yet") } }
        host.requireProfilePicker("paired")
    }

    /** Standalone: this app hosts the contract itself, so there is no reachability question to ask. */
    private fun goStandalone(host: ScreenHost) {
        val ctx = host.app
        ctx.prefs.setBackendChoice("standalone")
        ctx.prefs.setStandalone(true)
        ctx.prefs.setFixture("")
        ctx.hub = LocalHub.get(ctx.app)
        ctx.offline = false
        ctx.capabilities = null
        ctx.watch?.rechoose()
        host.requireProfilePicker("standalone")
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
        val row = LinearLayout(ctx.app)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(theme.px(24f), theme.px(20f), theme.px(24f), theme.px(20f))
        row.background = Views.rounded(theme.surface, theme.radius("listItem", 6f))
        row.isFocusable = true
        row.isClickable = true

        col2(
            ctx, row,
            Views.text(ctx, "li", 25f, theme.ink, hub.display(), bold = true, maxLines = 1),
            Views.text(ctx, "liMeta", 20f, theme.ink2, hub.url, maxLines = 1),
        )
        val go = Views.text(ctx, "li", 21f, theme.brand, "Use this hub", bold = true)
        val glp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        glp.marginStart = theme.px(24f)
        row.addView(go, glp)

        row.setOnClickListener { chooseHub(host, hub.url, hub.name) }
        row.setOnFocusChangeListener { v, has ->
            v.setBackgroundColor(if (has) theme.color("surfHover", "#2F2F2F") else theme.surface)
        }
        col.addView(row, lp(theme.px(10f)))
    }

    /** The typed-address case: a hub on another network, or one that has not been told to announce. */
    private fun addressField(ctx: AppContext, col: LinearLayout, host: ScreenHost) {
        val theme = ctx.theme
        val box = LinearLayout(ctx.app)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(theme.px(24f), theme.px(20f), theme.px(24f), theme.px(20f))
        box.background = Views.rounded(theme.surface, theme.radius("listItem", 6f))
        col.addView(box, lp(theme.px(10f)))

        box.addView(
            Views.text(ctx, "li", 22f, theme.ink2, "Hub address"),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )

        val field = EditText(ctx.app)
        field.hint = "http://<hub-host>:8788"
        field.setText(ctx.prefs.hubUrl(ctx.app.getString(R.string.hub_url)))
        field.setTextColor(theme.ink)
        field.setHintTextColor(theme.ink3)
        field.setTextSize(TypedValue.COMPLEX_UNIT_PX, theme.type("li", 24f))
        field.background = Views.rounded(theme.surface2, theme.radius("field", 6f), theme.px(1f), theme.line)
        field.setPadding(theme.px(16f), theme.px(12f), theme.px(16f), theme.px(12f))
        val inputType = android.text.InputType.TYPE_CLASS_TEXT or
            android.text.InputType.TYPE_TEXT_VARIATION_URI or
            android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        field.inputType = inputType
        val flp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        flp.topMargin = theme.px(10f)
        box.addView(field, flp)

        var current = field.text?.toString() ?: ""
        field.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { current = s?.toString() ?: "" }
        })

        val row = LinearLayout(ctx.app)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        val slp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        slp.topMargin = theme.px(14f)

        val save = Views.text(ctx, "button", 22f, theme.ink, "Connect", bold = true, gravity = Gravity.CENTER)
        save.setPadding(theme.px(24f), theme.px(14f), theme.px(24f), theme.px(14f))
        save.background = Views.rounded(theme.brand, theme.radius("button", 6f))
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
        Focus.attach(save, save, ctx, theme.radius("button", 6f), scale = false)
        row.addView(save, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        if (addressError != null) {
            val err = Views.text(ctx, "liMeta", 21f, theme.color("warn", "#F2C33A"), addressError ?: "", maxLines = 2)
            val elp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            elp.marginStart = theme.px(24f)
            row.addView(err, elp)
        }
        box.addView(row, slp)
    }

    // ---- row builders, the same shapes Settings uses ------------------------------------------

    private fun section(ctx: AppContext, col: LinearLayout, label: String) {
        col.addView(Views.text(ctx, "rowTitle", 26f, ctx.theme.ink, label, bold = true), lp(ctx.theme.px(38f)))
    }

    private fun infoRow(ctx: AppContext, col: LinearLayout, value: String) {
        val theme = ctx.theme
        val row = LinearLayout(ctx.app)
        row.setPadding(theme.px(24f), theme.px(16f), theme.px(24f), theme.px(16f))
        row.background = Views.rounded(theme.surface, theme.radius("listItem", 6f))
        row.addView(Views.text(ctx, "li", 22f, theme.ink2, value, maxLines = 3))
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
        val row = LinearLayout(ctx.app)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(theme.px(24f), theme.px(20f), theme.px(24f), theme.px(20f))
        row.background = Views.rounded(theme.surface, theme.radius("listItem", 6f))
        row.isFocusable = true
        row.isClickable = true

        row.addView(
            Views.text(ctx, "li", 25f, theme.ink, label, bold = true),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        if (value.isNotBlank()) {
            row.addView(
                Views.text(ctx, "liMeta", 20f, theme.ink2, value, maxLines = 1),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT),
            )
        }
        row.addView(
            Views.text(ctx, "li", 21f, theme.brand, action, bold = true),
            marginLp(theme.px(24f)),
        )

        row.setOnClickListener { onClick() }
        row.setOnFocusChangeListener { v, has ->
            v.setBackgroundColor(if (has) theme.color("surfHover", "#2F2F2F") else theme.surface)
        }
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
