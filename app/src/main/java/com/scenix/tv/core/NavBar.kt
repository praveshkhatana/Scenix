package com.scenix.tv.core

import android.animation.ValueAnimator
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
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
        // Transparent by default: on Home the rows scroll underneath this strip, and the screen that
        // owns the nav decides when it has to turn solid (NavBar.tint). A solid bar here would paint a
        // hard line across the billboard on every screen.
        bar.background = ColorDrawable(Color.TRANSPARENT)

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
            tv.isClickable = true
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
            // The hub sends no avatar art, so the profile marks itself with its own initial on the
            // profile's colour — the same reading the picker tiles use, so the same person looks the
            // same way in both places.
            val size = theme.px(34f)
            val avatar = android.widget.TextView(ctx.app)
            avatar.isFocusable = false // decoration: the chip next to it is the control
            avatar.background = Views.rounded(if (profile.isKids) theme.kids else theme.brand, size / 2)
            avatar.gravity = Gravity.CENTER
            avatar.includeFontPadding = false
            avatar.setTextColor(if (profile.isKids) theme.bg else Color.WHITE)
            avatar.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, theme.type("nav", 20f))
            avatar.text = profile.name.trim().take(1).uppercase().ifEmpty { "\u2022" }
            chip.addView(avatar, LinearLayout.LayoutParams(size, size))
            val name = Views.text(ctx, "nav", 22f, theme.ink, profile.name)
            val nlp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            nlp.marginStart = theme.px(12f)
            chip.addView(name, nlp)
            chip.isFocusable = true
            chip.isClickable = true
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
        gear.isClickable = true
        gear.setOnClickListener { host.show("settings") }
        gear.setOnFocusChangeListener { v, has -> v.setBackgroundColor(if (has) theme.surface2 else Color.TRANSPARENT) }
        right.addView(gear, glp)

        return bar
    }

    companion object {
        /**
         * Home calls this with the list's scroll offset. The strip stays clear while the billboard
         * fills the top of the screen and fades to the page background once content slides under it,
         * so the nav never sits on top of a bright poster. Same theme clock as everything else.
         */
        fun tint(bar: View, ctx: AppContext, scrollY: Int) {
            val theme = ctx.theme
            val d = bar.background as? ColorDrawable ?: return
            val at = theme.animMs("navFadeAt", 50)
            val target = if (at > 0 && scrollY >= at) 255 else 0
            if (d.alpha == target) return
            val ms = theme.animMs("navFadeMs", 200)
            if (ms <= 0) {
                d.alpha = target
                return
            }
            ValueAnimator.ofInt(d.alpha, target).apply {
                duration = ms.toLong()
                addUpdateListener { d.alpha = it.animatedValue as Int }
                start()
            }
        }
    }
}
