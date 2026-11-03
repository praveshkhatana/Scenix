package com.scenix.tv.core

import android.view.View

/**
 * The one entrance vocabulary every native screen shares.
 *
 * Every number is read from the active pack's `anim` tokens (theme.json), so a pack can retune the
 * whole app's motion without touching Kotlin: a screen fades in over `screenFadeMs`; packs whose
 * `motion` is "rise" also float their staged sections up from `fadeUpShift`, one `fadeUpStagger`
 * after another, for `fadeUpMs`. Chrome layers (`PlayerActivity`) and menu sheets use the same
 * fade helpers as the rest of the app — nothing animates except transform and opacity, exits are
 * shorter than entries, and with system animations disabled everything collapses to "just appears".
 */
object Motion {

    /** Direct children worth staging: the visible sections of a screen, capped so a long list
     *  does not stage its every row by hand. */
    private fun stagesOf(root: android.view.ViewGroup): List<View> =
        (0 until root.childCount)
            .map { root.getChildAt(it) }
            .filter { it.visibility == View.VISIBLE }
            .take(4)

    /** Call once, right after setContentView. Stages default to the root's visible sections. */
    fun enter(theme: Theme, root: View, staged: List<View>? = null) {
        if (!theme.motionEnabled) return
        val screenMs = theme.token("anim", "screenFadeMs", 180.0).toLong().coerceIn(60L, 400L)
        root.animate().cancel()
        root.alpha = 0f
        root.animate().alpha(1f).setDuration(screenMs).start()
        if (theme.motionStyle != "rise") return
        val ms = theme.token("anim", "fadeUpMs", 600.0).toLong().coerceIn(200L, 900L)
        val shift = theme.token("anim", "fadeUpShift", 20.0).toFloat()
        val stagger = theme.token("anim", "fadeUpStagger", 30.0).toLong().coerceIn(0L, 120L)
        val stages = staged ?: (root as? android.view.ViewGroup)?.let { stagesOf(it) } ?: emptyList()
        stages.forEachIndexed { i, v ->
            v.animate().cancel()
            v.alpha = 0f
            v.translationY = theme.px(shift).toFloat()
            v.animate().alpha(1f).translationY(0f)
                .setStartDelay(i * stagger).setDuration(ms).start()
        }
    }

    /** Fade any layer to full opacity over [ms], cancelling whatever it was doing. */
    fun inAlpha(view: View, theme: Theme, ms: Long) {
        view.animate().cancel()
        if (!theme.motionEnabled) {
            view.alpha = 1f
            return
        }
        if (view.alpha < 0.05f) view.alpha = 0f
        view.animate().alpha(1f).setDuration(ms).start()
    }

    /**
     * Fade a layer out and run [onGone] with the result hidden — the single way chrome and menus
     * leave, so an exit is always shorter than its entry and never leaves a half-visible layer.
     */
    fun outAlpha(view: View, theme: Theme, ms: Long, onGone: () -> Unit) {
        view.animate().cancel()
        if (!theme.motionEnabled || view.alpha < 0.05f) {
            onGone()
            return
        }
        view.animate().alpha(0f).setDuration(ms).withEndAction(onGone).start()
    }

    /** A menu sheet rises a little as it fades in — transform + opacity only. */
    fun sheetIn(view: View, theme: Theme) {
        if (!theme.motionEnabled) return
        val ms = theme.token("anim", "cardFadeInMs", 220.0).toLong().coerceIn(120L, 400L)
        val rise = theme.px(theme.token("anim", "fadeUpShift", 20.0).toFloat() / 2f).toFloat()
        view.animate().cancel()
        view.alpha = 0f
        view.translationY = rise
        view.animate().alpha(1f).translationY(0f).setDuration(ms).start()
    }
}
