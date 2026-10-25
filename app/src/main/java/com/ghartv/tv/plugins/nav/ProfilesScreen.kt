package com.scenix.tv.plugins.nav

import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.scenix.tv.R
import com.scenix.tv.core.AppContext
import com.scenix.tv.core.Focus
import com.scenix.tv.core.PinPad
import com.scenix.tv.core.Profile
import com.scenix.tv.core.ScreenHost
import com.scenix.tv.core.ScreenPlugin
import com.scenix.tv.core.Views

/**
 * "Who's watching?" — the entry screen, and the only gate the app itself owns.
 *
 * The profile list is the hub's (`GET /app/v1/profiles`), including its `isKids` and `hasPin` flags.
 * The PIN itself never leaves the TV: it is hashed into SharedPreferences. A profile the hub marks
 * protected but this TV has no PIN for is asked to set one on first use, so a family can lock a
 * profile from the app without a hub-side settings page.
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

        val manage = Views.text(ctx, "sub", 26f, theme.ink2, "Manage profiles \u00B7 hub \u00B7 player settings", gravity = Gravity.CENTER)
        val mlp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        mlp.topMargin = theme.px(52f)
        manage.isFocusable = true
        manage.setOnClickListener { host.show("settings") }
        Focus.attach(manage, manage, ctx, theme.radius("chip", 6f), scale = false)
        column.addView(manage, mlp)

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

    /** The PIN gate: set on first use, verify afterwards. */
    private fun gate(host: ScreenHost, root: FrameLayout, profile: Profile) {
        val ctx = host.app
        val needsPin = profile.hasPin || ctx.prefs.hasPin(profile.id)
        if (!needsPin) {
            host.enterProfile(profile)
            return
        }
        val setting = !ctx.prefs.hasPin(profile.id)
        val pad = PinPad(
            ctx = ctx,
            title = if (setting) "Set a PIN for ${profile.name}" else ctx.app.getString(R.string.pin_title),
            subtitle = if (setting) "This profile is marked protected. The PIN stays on this TV \u2014 it is stored hashed and never sent to the hub."
                else "Enter the PIN for ${profile.name}",
            onDone = { entered ->
                if (setting) {
                    ctx.prefs.setPin(profile.id, entered)
                    closePin(root)
                    host.enterProfile(profile)
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

    override fun onBack(host: ScreenHost): Boolean {
        if (pinOverlay != null) {
            (host.container() as? FrameLayout)?.let { closePin(it) }
            return true
        }
        return false
    }

    override fun onLeave(host: ScreenHost) {
        pinOverlay = null
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
