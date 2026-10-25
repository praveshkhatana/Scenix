package com.scenix.tv.plugins.rules

import com.scenix.tv.core.Item
import com.scenix.tv.core.Row
import com.scenix.tv.core.RuleCtx
import com.scenix.tv.core.RulePlugin
import java.util.Locale
import java.util.regex.Pattern

/**
 * Kids guard: on a kids profile nothing adult survives the pipeline, no matter which row it came in
 * on, and rows the hub explicitly scoped to grown-ups disappear.
 *
 * Two independent belts, both data-driven — the hub's `scope` field and the `maturity` string the
 * hub already sends (CBFC: "U", "U/A 7+", "U/A 13+", "A"). The maximum allowed age is a theme token,
 * so a family can move it without touching code. A blank maturity is treated as unknown and kept,
 * because dropping everything the hub did not classify would hide the catalogue.
 */
object KidsGuard : RulePlugin {
    override val id get() = "kids-guard"
    override val title get() = "Kids profile: no adult rows or items"
    override val order = 30

    /** Age a maturity label implies, or -1 when the label says nothing. */
    fun maturityAge(maturity: String): Int {
        val m = maturity.trim()
        if (m.isEmpty()) return -1
        val number = Pattern.compile("(\\d{1,2})").matcher(m.lowercase(Locale.US))
        if (number.find()) return number.group(1)?.toIntOrNull() ?: -1
        return when (m.lowercase(Locale.US)) {
            "u", "tv-y", "g" -> 0
            "tv-y7" -> 7
            "pg", "tv-pg", "tv-14" -> 13
            "a", "tv-ma", "r", "nc-17" -> 18
            else -> -1
        }
    }

    override fun applyRow(row: Row, rctx: RuleCtx): Row? {
        if (!rctx.profile.isKids) return row
        if (row.scope == "adult") return null
        return row
    }

    override fun applyItem(item: Item, rctx: RuleCtx): Item? {
        if (!rctx.profile.isKids) return item
        val maxAge = rctx.app.theme.token("kids", "maxAge", 7.0).toInt()
        val age = maturityAge(item.maturity)
        if (age > maxAge) return null
        return item
    }
}
