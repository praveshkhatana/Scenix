package com.scenix.tv.core

import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.scenix.tv.R

/**
 * The top nav: brand mark, then one entry per registered [ScreenPlugin] that wants to be in the nav
 * (the registry filters by profile scope, so a kids profile has no Search because
 * [ScreenPlugin.scope] said so — not because this file knows what Kids means), then the current
 * profile and Settings.
 *
 * Every screen gets a fresh one, because the nav is fixed while the rows scroll underneath it.
 */
class NavBar(private val ctx: AppContext, private val host: ScreenHost) {

    fun view(currentScreenId: String): View {
        val theme = ctx.theme
        val bar = LinearLayout(ctx.app)
        // Tagged so a screen opening can aim its first focus at content instead of at the nav strip.
        bar.tag = "nav"
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setPadding(theme.gutter, 0, theme.gutter, 0)
        bar.layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, theme.navHeight)
        bar.setBackgroundColor(theme.bg)

        val mark = Views.text(ctx, "brand", 34f, theme.brand, theme.brandMark(), bold = true)
        bar.addView(mark, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val items = LinearLayout(ctx.app)
        items.orientation = LinearLayout.HORIZONTAL
        items.gravity = Gravity.CENTER_VERTICAL
        bar.addView(items, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))

        for (screen in host.navScreens()) {
            val selected = screen.id == currentScreenId
            val tv = Views.text(
                ctx, "nav", 22f,
                if (selected) theme.ink else theme.color("navIdle", "#CCFFFFFF"),
                screen.title,
                bold = selected,
            )
            tv.setPadding(theme.px(18f), theme.px(10f), theme.px(18f), theme.px(10f))
            tv.isFocusable = true
            tv.setOnClickListener { host.show(screen.id) }
            tv.setOnFocusChangeListener { v, has ->
                (v as TextView).setTextColor(if (has) theme.ink else (if (selected) theme.ink else theme.color("navIdle", "#CCFFFFFF")))
                v.setBackgroundColor(if (has) theme.surface2 else Color.TRANSPARENT)
            }
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.marginStart = theme.px(6f)
            items.addView(tv, lp)
            if (selected) {
                val underline = View(ctx.app)
                underline.setBackgroundColor(theme.brand)
                val ulp = LinearLayout.LayoutParams(theme.px(30f), theme.px(3f))
                ulp.marginStart = theme.px(18f)
                items.addView(underline, ulp)
            }
        }

        // Right side: profile + settings.
        val right = LinearLayout(ctx.app)
        right.orientation = LinearLayout.HORIZONTAL
        right.gravity = Gravity.CENTER_VERTICAL
        bar.addView(right, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val profile = ctx.profile
        if (profile != null) {
            val chip = LinearLayout(ctx.app)
            chip.orientation = LinearLayout.HORIZONTAL
            chip.gravity = Gravity.CENTER_VERTICAL
            val avatar = View(ctx.app)
            val size = theme.px(34f)
            avatar.background = Views.rounded(if (profile.isKids) theme.kids else theme.brand, size / 2)
            chip.addView(avatar, LinearLayout.LayoutParams(size, size))
            val name = Views.text(ctx, "nav", 22f, theme.ink, profile.name)
            val nlp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            nlp.marginStart = theme.px(12f)
            chip.addView(name, nlp)
            chip.isFocusable = true
            chip.setOnClickListener { host.requireProfilePicker("switch") }
            Focus.attach(chip, chip, ctx, theme.radius("chip", 6f), scale = false)
            right.addView(chip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }

        val gear = ImageView(ctx.app)
        // This is the settings button, not a lock: a lock glyph read as "this profile is locked",
        // and the profile tiles are where a real lock is shown.
        gear.setImageResource(R.drawable.ic_settings)
        gear.setColorFilter(theme.ink2)
        val gs = theme.px(30f)
        val glp = LinearLayout.LayoutParams(gs, gs)
        glp.marginStart = theme.px(24f)
        gear.isFocusable = true
        gear.setOnClickListener { host.show("settings") }
        gear.setOnFocusChangeListener { v, has -> v.setBackgroundColor(if (has) theme.surface2 else Color.TRANSPARENT) }
        right.addView(gear, glp)

        return bar
    }
}
