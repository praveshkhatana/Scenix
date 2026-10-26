package com.scenix.tv.plugins.nav

import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import com.scenix.tv.core.AppContext
import com.scenix.tv.core.HubCb
import com.scenix.tv.core.HubError
import com.scenix.tv.core.Item
import com.scenix.tv.core.NavBar
import com.scenix.tv.core.Row
import com.scenix.tv.core.ScreenHost
import com.scenix.tv.core.Views
import com.scenix.tv.plugins.rows.RowAdapter
import com.scenix.tv.plugins.rows.statusRow

/**
 * The shared shape of every row-based screen: a fixed nav bar on top, one scrolling list of hub rows
 * under it, the rule pipeline applied on the way in.
 *
 * A screen that wants a different shape (Home, Detail, Player, Settings, Profiles) builds its own
 * view; a screen that is "these parts of the catalogue" is one of these, which is why adding one is
 * a new file plus one line in `PluginCatalog.kt`.
 *
 * Note what the screens filter on: the model's own fields — `Item.type` for Movies / Series / Live,
 * `Row.scope` for Kids, `Item.addedAt` for New, `Prefs.myList` for My List. No screen guesses what a
 * title is from its name.
 */
object BrowseHelper {

    fun render(
        host: ScreenHost,
        screenId: String,
        title: String,
        heading: Boolean = true,
        transform: (List<Row>) -> List<Row> = { it },
        headers: (List<Row>) -> List<Pair<View, Boolean>> = { emptyList() },
    ) {
        val ctx = host.app
        val container = host.container()
        val theme = ctx.theme

        val root = LinearLayout(ctx.app)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(theme.bg)
        container.addView(
            root,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )

        root.addView(NavBar(ctx, host).view(screenId))

        val (rv, adapter) = RowAdapter.newRecyclerView(ctx, host)
        root.addView(rv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        adapter.submit(listOf(statusRow(ctx, "Loading\u2026") to false), emptyList())

        ctx.hub.home(ctx.profile?.id) { page, error ->
            runOnUiThread {
                if (container.indexOfChild(root) < 0) return@runOnUiThread  // screen already replaced
                if (error != null && page == null) {
                    // 403 is a rule, not an outage: the profile simply does not see this screen.
                    val msg = if (HubError.forbidden(error)) hiddenMessage(ctx) else offlineMessage(ctx, error)
                    adapter.submit(listOf(statusRow(ctx, msg) to false), emptyList())
                    return@runOnUiThread
                }
                val incoming = page ?: emptyList()
                val rows = transform(ctx.applyRules(incoming))
                if (title.isBlank() || screenId == "home") ctx.lastHome = incoming
                val extra = ArrayList<Pair<View, Boolean>>()
                if (ctx.offline) extra.add(offlineChip(ctx) to false)
                if (heading && title.isNotBlank()) extra.add(heading(ctx, title) to false)
                extra.addAll(headers(incoming))
                if (rows.isEmpty() && extra.isEmpty()) {
                    extra.add(statusRow(ctx, emptyMessage(ctx)) to false)
                }
                adapter.submit(extra, rows)
            }
        }
    }

    // ---- reusable transforms -----------------------------------------------------------------

    /** Keep only rows that still hold items of one `type` (movie | series | channel | video). */
    fun byType(rows: List<Row>, type: String): List<Row> =
        rows.map { row -> row.copy(items = row.items.filter { it.type == type }) }
            .filter { it.items.isNotEmpty() }

    /** Live: anything the hub marked live, whatever its type string says. */
    fun live(rows: List<Row>): List<Row> =
        rows.map { row -> row.copy(items = row.items.filter { it.isLive() || it.type == "channel" }) }
            .filter { it.items.isNotEmpty() }

    /** Audience scope (the hub's `row.scope`, all | adult | kids). */
    fun byScope(rows: List<Row>, scope: String): List<Row> =
        rows.filter { it.scope == scope || it.scope == "all" }

    /**
     * Flatten the catalogue into one row sorted by a model field. `New & Popular` sorts by
     * `addedAt`; `My List` keeps the hub's order and only filters by the local list.
     */
    fun flatten(rows: List<Row>, id: String, title: String, card: String = "poster"): Row {
        val seen = LinkedHashSet<String>()
        val items = ArrayList<Item>()
        for (r in rows) for (i in r.items) if (seen.add(i.identity)) items.add(i)
        return Row(id = id, title = title, card = card, items = items)
    }

    fun heading(ctx: AppContext, title: String): View {
        val tv = Views.text(ctx, "h2", 56f, ctx.theme.ink, title, bold = true)
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.setMargins(ctx.theme.gutter, ctx.theme.px(10f), ctx.theme.gutter, ctx.theme.px(8f))
        tv.layoutParams = lp
        return tv
    }

    fun offlineMessage(ctx: AppContext, error: String): String {
        val hub = ctx.prefs.hubUrl(ctx.app.getString(com.scenix.tv.R.string.hub_url))
        return "Cannot reach the hub at $hub\n\n$error\n\n" +
            "Start the hub, or run the app in fixture mode (--es fixture assets/fixtures/home.json)."
    }

    fun emptyMessage(ctx: AppContext): String = "Nothing here yet \u2014 the hub sent no rows this profile can see."

    /** A kids profile getting 403 on an adult screen: say it plainly, do not make it look broken. */
    fun hiddenMessage(ctx: AppContext): String =
        "This screen is not part of ${ctx.profile?.name ?: "this"} profile."

    /**
     * The small chip that sits above the rows while the app is running on bundled fixtures. It is a
     * header, not a dialog and not a toast: the rows are usable, and it disappears the moment HubWatch
     * reaches the hub again.
     */
    fun offlineChip(ctx: AppContext): View {
        val theme = ctx.theme
        val tv = Views.text(
            ctx, "chip", 20f, theme.color("kids", "#F2C33A"),
            "hub offline \u2014 retrying \u00B7 showing bundled data",
        )
        tv.setPadding(theme.px(20f), theme.px(10f), theme.px(20f), theme.px(10f))
        tv.background = Views.rounded(
            theme.surface, theme.radius("chip", 6f), theme.px(1f), theme.color("kids", "#F2C33A"),
        )
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.setMargins(theme.gutter, theme.px(12f), theme.gutter, theme.px(4f))
        tv.layoutParams = lp
        return tv
    }

    /** Hub callbacks arrive on the hub's threads; every screen hops to the UI thread here. */
    fun runOnUiThread(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block()
        else Handler(Looper.getMainLooper()).post(block)
    }

    /** One-off endpoint call that ignores its screen once the container has been replaced. */
    fun <T> call(ctx: AppContext, container: View, request: (HubCb<T>) -> Unit, onResult: (T?, String?) -> Unit) {
        request { value, error ->
            runOnUiThread {
                if (!container.isAttachedToWindow) return@runOnUiThread
                onResult(value, error)
            }
        }
    }
}
