package com.scenix.tv.plugins.rules

import com.scenix.tv.core.Row
import com.scenix.tv.core.RuleCtx
import com.scenix.tv.core.RulePlugin

/**
 * Per-profile "hide this row": Settings → Hidden rows writes a list of row ids into SharedPreferences
 * and every screen stops showing them.
 *
 * This is why the row id is stable in the hub: hiding a row is a client preference, not a content
 * change, so it survives a hub restart and does not need a hub endpoint.
 */
object HideRows : RulePlugin {
    override val id get() = "hide-rows"
    override val title get() = "Hide rows per profile (Settings)"
    override val order = 20

    override fun applyRow(row: Row, rctx: RuleCtx): Row? {
        val hidden = rctx.app.prefs.hiddenRows(rctx.profile.id)
        if (row.id in hidden) return null
        return row
    }
}
