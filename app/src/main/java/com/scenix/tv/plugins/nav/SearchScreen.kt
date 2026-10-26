package com.scenix.tv.plugins.nav

import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import com.scenix.tv.core.NavBar
import com.scenix.tv.core.Row
import com.scenix.tv.core.ScreenHost
import com.scenix.tv.core.ScreenPlugin
import com.scenix.tv.core.Views
import com.scenix.tv.plugins.rows.RowAdapter
import com.scenix.tv.plugins.rows.statusRow

/**
 * Search. `GET /app/v1/search?q=` does the matching — the hub already knows titles, aliases,
 * languages and genres; the app sends a string.
 *
 * `scope = "adult"`: on a kids profile the nav never offers this screen at all, because a free-text
 * box is the one input a child can use to leave the kids corner.
 *
 * Opened with an argument (a genre chip on Home) it runs that query straight away.
 */
object SearchScreen : ScreenPlugin {
    override val id get() = "search"
    override val title get() = "Search"
    override val route get() = "search"
    override val scope get() = "adult"
    override val order = 90

    private var lastQuery: String = ""

    override fun render(host: ScreenHost) {
        val ctx = host.app
        val theme = ctx.theme
        val container = host.container()

        val root = LinearLayout(ctx.app)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(theme.bg)
        container.addView(
            root,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        root.addView(NavBar(ctx, host).view(id))

        val bar = LinearLayout(ctx.app)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        val blp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        blp.setMargins(theme.gutter, theme.px(20f), theme.gutter, theme.px(10f))
        root.addView(bar, blp)

        val field = EditText(ctx.app)
        field.hint = ctx.app.getString(com.scenix.tv.R.string.search_hint)
        field.setText(lastQuery)
        field.setSelection(field.text.length)
        field.setTextColor(theme.ink)
        field.setHintTextColor(theme.ink3)
        field.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, theme.type("searchField", 30f))
        field.setPadding(theme.px(24f), theme.px(16f), theme.px(24f), theme.px(16f))
        field.background = Views.rounded(theme.surface2, theme.radius("field", 8f), theme.px(1f), theme.line)
        bar.addView(field, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val (rv, adapter) = RowAdapter.newRecyclerView(ctx, host)
        root.addView(rv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        adapter.submit(listOf(statusRow(ctx, "Type to search the hub.") to false), emptyList())

        field.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val q = s?.toString()?.trim() ?: ""
                run(host, container, adapter, q)
            }
        })

        field.post {
            field.requestFocus()
            if (!host.arg().isNullOrBlank() && lastQuery.isBlank()) {
                field.setText(host.arg())
            }
        }
    }

    private fun run(host: ScreenHost, container: View, adapter: RowAdapter, q: String) {
        val ctx = host.app
        lastQuery = q
        if (q.isBlank()) {
            adapter.submit(listOf(statusRow(ctx, "Type to search the hub.") to false), emptyList())
            return
        }
        ctx.hub.search(q, ctx.profile?.id) { rows, error ->
            BrowseHelper.runOnUiThread {
                if (!container.isAttachedToWindow) return@runOnUiThread
                if (error != null && rows == null) {
                    val msg = if (com.scenix.tv.core.HubError.forbidden(error)) {
                        BrowseHelper.hiddenMessage(ctx)
                    } else {
                        BrowseHelper.offlineMessage(ctx, error)
                    }
                    adapter.submit(listOf(statusRow(ctx, msg) to false), emptyList())
                    return@runOnUiThread
                }
                val found: List<Row> = ctx.applyRules(rows ?: emptyList())
                val headers = ArrayList<Pair<View, Boolean>>()
                if (found.sumOf { it.items.size } == 0) {
                    headers.add(statusRow(ctx, "No matches for \u201C$q\u201D.") to false)
                } else {
                    headers.add(BrowseHelper.heading(ctx, "Results for \u201C$q\u201D") to false)
                }
                adapter.submit(headers, found)
            }
        }
    }
}
