package com.scenix.tv.plugins.nav

import android.view.ViewGroup
import android.widget.LinearLayout
import com.scenix.tv.core.NavBar
import com.scenix.tv.core.ScreenHost
import com.scenix.tv.core.ScreenPlugin
import com.scenix.tv.plugins.rows.RowAdapter
import com.scenix.tv.plugins.rows.statusRow

/**
 * "Explore all ›" lands here: one hub row, fetched in full from `GET /app/v1/row/<id>` instead of
 * the trimmed preview `/home` gives.
 *
 * Not in the nav (`showInNav = false`) — it is reached only from a row header, and the row id is the
 * argument the host passed in.
 */
object RowScreen : ScreenPlugin {
    override val id get() = "row"
    override val title get() = "Row"
    override val route get() = "row"
    override val showInNav = false
    override val order = 200

    override fun render(host: ScreenHost) {
        val ctx = host.app
        val theme = ctx.theme
        val container = host.container()
        val rowId = host.arg()

        val root = LinearLayout(ctx.app)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(theme.bg)
        container.addView(
            root,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        root.addView(NavBar(ctx, host).view(id))

        val (rv, adapter) = RowAdapter.newRecyclerView(ctx, host)
        root.addView(rv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        if (rowId.isNullOrBlank()) {
            adapter.submit(listOf(statusRow(ctx, "No row requested.") to false), emptyList())
            return
        }
        adapter.submit(listOf(statusRow(ctx, "Loading\u2026") to false), emptyList())

        ctx.hub.row(rowId, ctx.profile?.id) { row, error ->
            BrowseHelper.runOnUiThread {
                if (container.indexOfChild(root) < 0) return@runOnUiThread
                if (row == null) {
                    // 403 on a row endpoint = this profile is not shown that row. Quiet, not an error.
                    val msg = when {
                        error != null && com.scenix.tv.core.HubError.forbidden(error) -> BrowseHelper.hiddenMessage(ctx)
                        error != null -> BrowseHelper.offlineMessage(ctx, error)
                        else -> "The hub has no row \"$rowId\"."
                    }
                    adapter.submit(listOf(statusRow(ctx, msg) to false), emptyList())
                    return@runOnUiThread
                }
                val processed = ctx.applyRules(listOf(row)).firstOrNull() ?: row
                val headers = ArrayList<Pair<android.view.View, Boolean>>()
                if (processed.title.isNotBlank()) headers.add(BrowseHelper.heading(ctx, processed.title) to false)
                // The row's own header would repeat the heading, so the strip prints without a title.
                adapter.submit(headers, listOf(processed.copy(title = "")))
            }
        }
    }
}
