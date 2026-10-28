package com.scenix.tv.plugins.nav

import android.graphics.Color
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.scenix.tv.R
import com.scenix.tv.core.AppContext
import com.scenix.tv.core.Focus
import com.scenix.tv.core.LocalHub
import com.scenix.tv.core.PinPad
import com.scenix.tv.core.Profile
import com.scenix.tv.core.ScreenHost
import com.scenix.tv.core.ScreenPlugin
import com.scenix.tv.core.Views
import org.json.JSONObject

/**
 * "Who's watching?" — the entry screen, and the only gate the app itself owns.
 *
 * The profile list is the backend's (`GET /app/v1/profiles`), including its `isKids` and `hasPin`
 * flags. A backend that says it has `profileEdit` — this TV on its own — is also asked to take a new
 * one here, so a household is not obliged to have created its people somewhere else first. The PIN
 * itself never leaves the TV: it is hashed into SharedPreferences. A profile marked protected but with
 * no PIN on this TV is asked to set one on first use, so a profile can be locked from the app without
 * a settings page anywhere else.
 *
 * Not in the nav: the app reaches it through `requireProfilePicker`, and Back from it exits.
 */
object ProfilesScreen : ScreenPlugin {
    override val id get() = "profiles"
    override val title get() = "Profiles"
    override val route get() = "profiles"
    override val showInNav = false
    override val order = 1

    private var pinOverlay: View? = null
    private var addOverlay: View? = null

    override fun render(host: ScreenHost) {
        val ctx = host.app
        val theme = ctx.theme
        val container = host.container()

        val root = FrameLayout(ctx.app)
        root.setBackgroundColor(theme.bg)
        container.addView(
            root,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )

        val column = LinearLayout(ctx.app)
        column.orientation = LinearLayout.VERTICAL
        column.gravity = Gravity.CENTER
        root.addView(
            column,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER),
        )

        column.addView(
            Views.text(ctx, "h2", 56f, theme.ink, ctx.app.getString(R.string.profiles_title), bold = true, gravity = Gravity.CENTER),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        val why = Views.text(ctx, "sub", 24f, theme.ink2, ctx.app.getString(R.string.manage_profiles), gravity = Gravity.CENTER)
        val whyLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        whyLp.topMargin = theme.px(14f)
        whyLp.bottomMargin = theme.px(46f)
        column.addView(why, whyLp)

        val grid = RecyclerView(ctx.app)
        grid.layoutManager = GridLayoutManager(ctx.app, 5)
        grid.itemAnimator = null
        grid.isFocusable = false
        grid.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        val glp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        glp.gravity = Gravity.CENTER
        column.addView(grid, glp)

        val manage = Views.text(
            ctx, "sub", 26f, theme.ink2,
            "Manage profiles \u00B7 ${if (ctx.prefs.standalone()) "this TV" else "hub"} \u00B7 player settings",
            gravity = Gravity.CENTER,
        )
        val mlp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        mlp.topMargin = theme.px(52f)
        manage.isFocusable = true
        manage.setOnClickListener { host.show("settings") }
        Focus.attach(manage, manage, ctx, theme.radius("chip", 6f), scale = false)
        column.addView(manage, mlp)

        // A backend that keeps its profiles itself can be asked for one here. A hub does not say so,
        // and its owner's own settings page stays the place where people are created.
        if (ctx.hub is LocalHub && ctx.hasCapability("profileEdit")) {
            val add = Views.text(ctx, "sub", 26f, theme.brand, "Add a profile", gravity = Gravity.CENTER)
            val alp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            alp.topMargin = theme.px(26f)
            add.isFocusable = true
            add.setOnClickListener { showAdd(host, root) }
            Focus.attach(add, add, ctx, theme.radius("chip", 6f), scale = false)
            column.addView(add, alp)
        }

        grid.adapter = ProfilesAdapter(ctx, host) { profile -> gate(host, root, profile) }

        ctx.hub.profiles { profiles, error ->
            BrowseHelper.runOnUiThread {
                if (container.indexOfChild(root) < 0) return@runOnUiThread
                val list: List<Profile> = if (profiles.isNullOrEmpty()) {
                    // No hub profiles: one local profile keeps the app usable (fixture / hub down).
                    listOf(Profile(id = "default", name = ctx.app.getString(R.string.app_name)))
                } else profiles
                (grid.adapter as ProfilesAdapter).submit(list)
                if (error != null && profiles.isNullOrEmpty()) {
                    why.text = "Hub profiles unavailable \u2014 " + error
                    why.setTextColor(theme.brand)
                }
                Focus.focusFirst(grid)
                // RecyclerView has no children until it has been measured, so the call above usually
                // finds nothing and the "Manage profiles" link below the grid is the only focusable
                // view on the screen - which is how one OK press opened Settings instead of a profile.
                grid.post { Focus.focusFirst(grid) }
            }
        }
    }

    /**
     * The PIN gate.
     *
     * The hub owns a profile PIN. This screen used to treat "the hub says there is a PIN" as "set a PIN
     * now", which let anyone overwrite a protected profile's code on first use — the reported "PIN isn't
     * working". It now verifies against the hub whenever the hub says the profile is locked, and only
     * falls back to this TV's local hash for standalone mode (this app hosting its own profiles).
     */
    private fun gate(host: ScreenHost, root: FrameLayout, profile: Profile) {
        val ctx = host.app
        val onHub = ctx.hub !is LocalHub
        val hubLocked = onHub && profile.hasPin
        val localLocked = ctx.prefs.hasPin(profile.id)
        if (!hubLocked && !localLocked) {
            host.enterProfile(profile)
            return
        }
        val pad = PinPad(
            ctx = ctx,
            title = ctx.app.getString(R.string.pin_title),
            subtitle = "Enter the PIN for ${profile.name}",
            onDone = { entered ->
                if (hubLocked) {
                    ctx.hub.unlockProfile(profile.id, entered) { ok, err ->
                        BrowseHelper.runOnUiThread {
                            if (ok == true) {
                                closePin(root)
                                host.enterProfile(profile)
                            } else {
                                val msg = if (err != null && err.contains("429"))
                                    "Too many attempts \u2014 wait a few minutes"
                                else "Wrong PIN \u2014 try again"
                                (pinOverlay?.tag as? PinPad)?.showError(msg)
                            }
                        }
                    }
                } else if (ctx.prefs.checkPin(profile.id, entered)) {
                    closePin(root)
                    host.enterProfile(profile)
                } else {
                    (pinOverlay?.tag as? PinPad)?.showError("Wrong PIN \u2014 try again")
                }
            },
            onCancel = { closePin(root) },
        )
        val overlay = pad.view()
        overlay.tag = pad
        pinOverlay = overlay
        root.addView(
            overlay,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
    }

    /**
     * One name and one question — whether this is a child's profile — written into this TV's own
     * profile list. Nothing else about a person is asked for here: their languages and their ceilings
     * are set under Settings, once they exist.
     */
    private fun showAdd(host: ScreenHost, root: FrameLayout) {
        val ctx = host.app
        val theme = ctx.theme
        val local = ctx.hub as? LocalHub ?: return

        val panel = LinearLayout(ctx.app)
        panel.orientation = LinearLayout.VERTICAL
        panel.setPadding(theme.px(46f), theme.px(38f), theme.px(46f), theme.px(38f))
        panel.background = Views.rounded(theme.surface, theme.radius("modal", 10f))

        panel.addView(
            Views.text(ctx, "h3", 34f, theme.ink, "A new profile"),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        val hint = Views.text(
            ctx, "sub", 24f, theme.ink2,
            "A name for this person. A child's shelf carries only what a maturity label allows.",
        )
        val hlp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        hlp.topMargin = theme.px(12f)
        hlp.bottomMargin = theme.px(24f)
        panel.addView(hint, hlp)

        val field = EditText(ctx.app)
        field.hint = "Name"
        field.setTextColor(theme.ink)
        field.setHintTextColor(theme.ink3)
        field.setTextSize(TypedValue.COMPLEX_UNIT_PX, theme.type("li", 24f))
        field.inputType = android.text.InputType.TYPE_CLASS_TEXT or
            android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        field.background = Views.rounded(theme.surface2, theme.radius("field", 8f), theme.px(1f), theme.line)
        field.setPadding(theme.px(18f), theme.px(14f), theme.px(18f), theme.px(14f))
        panel.addView(
            field,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )

        val row = LinearLayout(ctx.app)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.END
        for (choice in listOf("Adult" to "adult", "Child" to "kids")) {
            val b = Views.text(ctx, "button", 22f, theme.ink, choice.first, bold = true, gravity = Gravity.CENTER)
            b.setPadding(theme.px(26f), theme.px(14f), theme.px(26f), theme.px(14f))
            b.background = Views.rounded(if (choice.second == "kids") theme.kids else theme.brand, theme.radius("button", 6f))
            b.isFocusable = true
            b.setOnClickListener {
                val name = field.text.toString().trim()
                if (name.isEmpty()) {
                    field.requestFocus()
                    return@setOnClickListener
                }
                // The same body a hub creates a profile from: a name and which shelf it watches. A PIN is
                // not set here, because a hub takes a code only on the edit that follows.
                val created = local.addProfile(JSONObject().put("name", name).put("kind", choice.second))
                if (created.has("error")) {
                    // Say why in the backend's own words rather than closing the panel on a name that did
                    // not land — a second "Asha" is the common case, and a silent panel looks like a freeze.
                    hint.setTextColor(theme.ink3)
                    hint.text = created.optString("error", "that name could not be used")
                    field.requestFocus()
                    return@setOnClickListener
                }
                closeAdd(root)
                host.show("profiles")
            }
            Focus.attach(b, b, ctx, theme.radius("button", 6f), scale = false)
            val blp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            blp.leftMargin = theme.px(18f)
            row.addView(b, blp)
        }
        val rlp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        rlp.topMargin = theme.px(28f)
        panel.addView(row, rlp)

        val scrim = FrameLayout(ctx.app)
        scrim.setBackgroundColor(theme.color("scrim", "#BF000000"))
        scrim.addView(
            panel,
            FrameLayout.LayoutParams(theme.px(820f), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER),
        )
        addOverlay = scrim
        root.addView(
            scrim,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        field.requestFocus()
    }

    private fun closeAdd(root: FrameLayout) {
        addOverlay?.let { root.removeView(it) }
        addOverlay = null
    }

    override fun onBack(host: ScreenHost): Boolean {
        if (pinOverlay != null) {
            (host.container() as? FrameLayout)?.let { closePin(it) }
            return true
        }
        if (addOverlay != null) {
            (host.container() as? FrameLayout)?.let { closeAdd(it) }
            return true
        }
        return false
    }

    override fun onLeave(host: ScreenHost) {
        pinOverlay = null
        addOverlay = null
    }

    private fun closePin(root: FrameLayout) {
        pinOverlay?.let { root.removeView(it) }
        pinOverlay = null
    }

    /** The 176 px avatar grid: initial tile, kids tiles tinted, lock glyph when protected. */
    private class ProfilesAdapter(
        val ctx: AppContext,
        val host: ScreenHost,
        val onClick: (Profile) -> Unit,
    ) : RecyclerView.Adapter<ProfilesAdapter.Holder>() {

        private var items: List<Profile> = emptyList()

        fun submit(list: List<Profile>) {
            items = list
            notifyDataSetChanged()
        }

        class Holder(
            val item: LinearLayout,
            val tile: FrameLayout,
            val name: TextView,
            val initial: TextView,
            val lock: ImageView,
        ) : RecyclerView.ViewHolder(item)

        override fun getItemCount(): Int = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val theme = ctx.theme
            val size = Math.round(theme.type("avatar", 176f))

            val item = LinearLayout(ctx.app)
            item.orientation = LinearLayout.VERTICAL
            item.gravity = Gravity.CENTER_HORIZONTAL
            item.layoutParams = RecyclerView.LayoutParams(size + theme.px(28f), ViewGroup.LayoutParams.WRAP_CONTENT)

            val tile = FrameLayout(ctx.app)
            item.addView(tile, LinearLayout.LayoutParams(size, size))

            val initial = Views.text(ctx, "avatarText", 64f, theme.ink, "", bold = true, gravity = Gravity.CENTER)
            tile.addView(initial, FrameLayout.LayoutParams(size, size))

            val lock = ImageView(ctx.app)
            lock.setImageResource(R.drawable.ic_lock)
            val ls = Math.round(theme.type("lockGlyph", 52f))
            val llp = FrameLayout.LayoutParams(ls, ls, Gravity.BOTTOM or Gravity.END)
            llp.setMargins(theme.px(10f), 0, theme.px(10f), theme.px(10f))
            tile.addView(lock, llp)

            val name = Views.text(ctx, "profileName", 24f, theme.ink2, "", gravity = Gravity.CENTER)
            val nlp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            nlp.topMargin = theme.px(16f)
            item.addView(name, nlp)

            return Holder(item, tile, name, initial, lock)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val theme = ctx.theme
            val profile = items[position]

            // The mockup tints kid tiles amber and grown-up tiles brand red.
            holder.tile.background = if (profile.isKids) {
                Views.gradient(intArrayOf(theme.kids, theme.color("kidsDeep", "#D9A22B")), theme.radius("avatar", 10f))
            } else {
                Views.gradient(intArrayOf(theme.brand, theme.color("brandDeep", "#B00710")), theme.radius("avatar", 10f))
            }

            holder.initial.text = profile.name.take(1).uppercase()
            holder.initial.setTextColor(if (profile.isKids) Color.BLACK else theme.ink)
            holder.name.text = profile.name
            holder.name.setTextColor(if (profile.isKids) theme.kids else theme.ink2)

            val protectedProfile = profile.hasPin || ctx.prefs.hasPin(profile.id)
            holder.lock.visibility = if (protectedProfile) View.VISIBLE else View.GONE
            holder.lock.setColorFilter(if (profile.isKids) Color.BLACK else theme.ink)

            Focus.attach(holder.item, holder.tile, ctx, theme.radius("avatar", 10f), scale = true) {
                onClick(profile)
            }
        }
    }
}
