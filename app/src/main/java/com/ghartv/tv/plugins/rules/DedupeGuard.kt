package com.scenix.tv.plugins.rules

import com.scenix.tv.core.Item
import com.scenix.tv.core.Row
import com.scenix.tv.core.RuleCtx
import com.scenix.tv.core.RulePlugin

/**
 * Dedupe guard: the same title never shows up twice in one row.
 *
 * The hub merges several providers into one identity, and two of its rows can hand back the same
 * item (or a provider can list one title twice under two ids). Without this the user sees the same
 * poster side by side and D-pad "next" looks broken. Runs on the row, so it is stateless and safe
 * with recycled views; the first copy wins.
 */
object DedupeGuard : RulePlugin {
    override val id get() = "dedupe"
    override val title get() = "Dedupe guard (one title, one card per row)"
    override val order = 10

    override fun applyRow(row: Row, rctx: RuleCtx): Row {
        if (row.items.size < 2) return row
        val seen = HashSet<String>(row.items.size)
        val kept = ArrayList<Item>(row.items.size)
        for (item in row.items) {
            val key = item.identity.ifBlank { "norm:${item.title.slug()}-${item.year}" }
            if (seen.add(key)) kept.add(item)
        }
        if (kept.size == row.items.size) return row
        return row.copy(items = kept)
    }

    /** Same normalisation the hub uses for its merge key, so the two agree. */
    private fun String.slug(): String =
        lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
}
