package com.scenix.tv.plugins.rules

import com.scenix.tv.core.Item
import com.scenix.tv.core.Row
import com.scenix.tv.core.RuleCtx
import com.scenix.tv.core.RulePlugin

/**
 * "Continue watching" is the one row with a promise attached: press OK and this picks up where you
 * stopped. Two things break that promise when the hub's row is taken at face value.
 *
 * A live channel cannot be resumed — it is the same stream whether or not you watched it — and a
 * title with nothing watched is not a continuation, it is a recommendation wearing the wrong clothes.
 * The hub sends both (`type: "channel"` in the row, and a `progress` of `0 of 0`), so the app drops
 * them here rather than letting the row fill up with things that do nothing.
 *
 * Rules are the right place for this: it is content policy, not card drawing, and the same filter
 * then applies on the "Explore all" screen for free.
 */
object ContinueGuard : RulePlugin {
    override val id get() = "continue-guard"
    override val title get() = "Continue watching: no live TV, nothing unwatched"
    override val order = 30

    override fun applyRow(row: Row, rctx: RuleCtx): Row? {
        if (!isContinue(row)) return row
        val kept: List<Item> = row.items.filter { it.isLive().not() && hasStarted(it) }
        if (kept.isEmpty()) return null
        return row.copy(items = kept)
    }

    private fun isContinue(row: Row): Boolean = row.id == "continue" || row.card == "continue"

    /**
     * Watched far enough to be worth resuming. `fraction` needs a total length from the hub, so a
     * half-watched title whose length the hub does not know is kept on its position alone.
     */
    private fun hasStarted(item: Item): Boolean {
        val p = item.progress ?: return false
        return p.fraction > MIN_FRACTION || p.positionMs > MIN_POSITION_MS
    }

    private const val MIN_FRACTION = 0.005f
    private const val MIN_POSITION_MS = 20_000L
}
