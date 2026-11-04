package com.scenix.tv

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.scenix.tv.core.AppContext
import com.scenix.tv.core.Motion
import com.scenix.tv.core.Screens
import com.scenix.tv.core.Focus
import com.scenix.tv.core.Views
import org.json.JSONArray
import org.json.JSONObject

/**
 * Live TV, acting as live TV: a wall of channel tiles the moment you open the tab, and a press of OK
 * tunes one — the same Player the Home Live row uses.
 *
 * Two modes, both in this screen, because they answer two different questions:
 *  · **All channels** — the wall. Every channel this profile may watch, found by name or genre.
 *  · **My lineup** — the list, in the viewer's own order. A lineup is the channel list the remote walks:
 *    CH UP/DOWN follow this order, and the numbers on the tiles are the places in it. Channels are moved
 *    by pressing LEFT/RIGHT on the one that is highlighted, added from the wall, and taken out again;
 *    one switch decides whether the rest of the wall stays on Live TV or only the lineup does.
 *
 * The list comes from `/app/v1/live/channels` — the same list the player's zap ring walks, so a channel
 * the manager offers is always a channel the remote can reach. The lineup is written to the profile's
 * prefs (`live.only` / `live.order`), never to the household's settings, which is what makes it one
 * person's choice rather than the house's. On a hub too old for that route the screen falls back to
 * `live.channels` in `/app/v1/settings`, which is the household's own playlist selection.
 */
class LiveChannelsActivity : Activity() {

    private enum class Mode { WALL, LINEUP }

    private lateinit var app: AppContext
    private lateinit var profileId: String
    private lateinit var grid: RecyclerView
    private lateinit var status: TextView
    private var wall: LiveWall? = null
    // A hub with no /app/v1/live/channels route: the household playlist's published channels.
    private var channels: JSONArray? = null
    private var only: MutableList<String> = ArrayList()
    private var order: MutableList<String> = ArrayList()
    private var mode = Mode.WALL
    private var picker: View? = null
    // The row that opened the last picker; it takes focus back when the picker
    // closes, so the remote never lands on the page's back control by accident.
    private var pickerReturn: View? = null
    private var filter = ""
    private var rootView: FrameLayout? = null
    private var modeButtons = HashMap<Mode, TextView>()
    private val api by lazy { BrowseApi(applicationContext, (application as ScenixApp).prefs) }

    /** Text standing on the pack's wall; light packs override the wall ink, dark packs keep theirs. */
    private val wallInk get() = app.theme.colorOr("wallInk", app.theme.ink)
    private val wallInk2 get() = app.theme.colorOr("wallInk2", app.theme.ink2)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        app = (application as ScenixApp).ctx
        profileId = intent?.getStringExtra(EXTRA_PROFILE).orEmpty()
        if (profileId.isBlank()) { finish(); return }

        val theme = app.theme
        val root = FrameLayout(this).apply {
            val wallArt = Screens.wall(app)
            if (wallArt != null) background = wallArt else setBackgroundColor(theme.bg)
        }
        rootView = root
        grid = RecyclerView(this).apply {
            layoutManager = GridLayoutManager(this@LiveChannelsActivity, 6)
            setHasFixedSize(true)
            setItemViewCacheSize(32)
            itemAnimator = null
            clipToPadding = false
            clipChildren = false
            setPadding(theme.gutter, theme.px(112f), theme.gutter, theme.px(56f))
            setBackgroundColor(Color.TRANSPARENT)
        }
        root.addView(grid, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        root.addView(topBar(), FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, theme.px(96f)
        ))
        status = Views.text(app, "sub", 21f, wallInk2, "Reading channels…", gravity = Gravity.CENTER)
        root.addView(status, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, theme.px(64f)
        ).apply { gravity = Gravity.BOTTOM })
        setContentView(root)
        Motion.enter(app.theme, root)
        load()
    }

    /** A floating bar over the wall: a feather chevron home, the tab's name, the two modes, and search. */
    private fun topBar(): View {
        val theme = app.theme
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(theme.gutter, theme.px(10f), theme.gutter, 0)
        }
        val back = ImageView(this).apply {
            setImageResource(R.drawable.ic_chevron_left)
            setColorFilter(wallInk, android.graphics.PorterDuff.Mode.SRC_IN)
            scaleType = ImageView.ScaleType.FIT_CENTER
            isFocusable = true
            isClickable = true
            setOnClickListener { finish() }
        }
        Focus.attach(back, back, app, theme.px(9f), scale = false, elevate = false,
            arrow = downTo(grid))
        bar.addView(back, LinearLayout.LayoutParams(theme.px(34f), theme.px(34f)))
        bar.addView(Views.text(app, "rowTitle", 26f, wallInk, "Live TV", bold = true).apply {
            setPadding(theme.px(18f), 0, 0, 0)
        })
        // The two modes, as the pack's own chips: the wall to find a channel, the lineup to order it.
        modeButtons = HashMap()
        for ((modeId, label) in listOf(
            Mode.WALL to "All channels",
            Mode.LINEUP to "My lineup" + if (order.isEmpty()) "" else " · ${order.size}",
        )) {
            val chip = modeChip(label)
            modeButtons[modeId] = chip
            chip.isFocusable = true
            chip.isClickable = true
            chip.setOnClickListener { setMode(modeId) }
            Focus.attach(chip, chip, app, theme.px(8f), scale = false, elevate = false,
                arrow = downTo(grid))
            // A WRAP_CONTENT chip sat on top of its neighbour once three of them shared the bar.
            // Give each one its label's measured width plus padding, with the pack's action width as
            // the floor.
            bar.addView(chip, LinearLayout.LayoutParams(
                maxOf(theme.space("rowAction", 160f).toInt(), theme.px(label.length * 10f + 44f)),
                theme.px(52f)
            ).apply { marginStart = theme.px(14f) })
        }
        // 383 tiles is a wall, not a shelf: the way to channel 214 is to name it, not to scroll.
        val search = Views.text(app, "li", 19f, wallInk,
            if (filter.isBlank()) "Search" else "Search: $filter  ✕", bold = true, gravity = Gravity.CENTER)
        search.background = Views.rounded(theme.surface2, theme.px(8f))
        search.isFocusable = true
        search.isClickable = true
        search.setOnClickListener { askFilter() }
        Focus.attach(search, search, app, theme.px(8f), scale = false, elevate = false)
        bar.addView(search, LinearLayout.LayoutParams(
            theme.px(if (filter.isBlank()) 170f else 340f), theme.px(52f)
        ).apply {
            marginStart = theme.px(22f)
        })
        paintModeChips()
        return bar
    }

    private fun modeChip(label: String): TextView =
        Views.text(app, "li", 19f, wallInk, label, bold = true, gravity = Gravity.CENTER)

    /**
     * The bar floats over the wall; a bare D-pad DOWN from a chip would land on nothing. Hand the key
     * to the grid so the top bar and the wall are one remote path.
     */
    private fun downTo(target: View): (Int) -> Boolean = arrow@{ key ->
        if (key != KeyEvent.KEYCODE_DPAD_DOWN) return@arrow false
        target.requestFocus()
        true
    }

    private fun setMode(next: Mode) {
        if (mode == next) return
        mode = next
        grid.scrollToPosition(0)
        paintModeChips()
        paint(focusAt = 0)
    }

    private fun paintModeChips() {
        val theme = app.theme
        for ((modeId, chip) in modeButtons) {
            val on = modeId == mode
            chip.background = Views.rounded(
                if (on) theme.brand else theme.surface2, theme.px(8f),
            )
            chip.setTextColor(if (on) Color.WHITE else wallInk)
        }
    }

    private fun askFilter() {
        startActivityForResult(
            Intent(this, TextEntryActivity::class.java)
                .putExtra(TextEntryActivity.EXTRA_TITLE, "Find a channel")
                .putExtra(TextEntryActivity.EXTRA_VALUE, filter)
                .putExtra(TextEntryActivity.EXTRA_HINT, "a word in the channel name — empty shows the whole wall")
                .putExtra(TextEntryActivity.EXTRA_PLAIN_TEXT, true),
            REQ_FILTER,
        )
    }

    @Deprecated("Activity result API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_FILTER || resultCode != RESULT_OK) return
        filter = data?.getStringExtra(TextEntryActivity.EXTRA_RESULT).orEmpty().trim()
        setContentView(rootView) // the top bar's chip has to change its label
        paint()
    }

    private fun load() {
        status.text = "Reading channels…"
        var pending = 2
        fun done() {
            pending--
            if (pending == 0) { status.text = ""; paint() }
        }
        // One source for the wall and the player's zap ring. `liveChannels` carries the profile's rules,
        // lineup, numbers, health and what is on now; an older hub 404s and the settings list is used.
        api.liveChannels(profileId) { w, error ->
            if (isFinishing || isDestroyed) return@liveChannels
            if (w != null && w.channels.isNotEmpty()) {
                wall = w
                only = w.only.toMutableList()
                order = w.order.toMutableList()
            } else if (error != null) {
                status.text = error
            }
            done()
        }
        api.settings { root, error ->
            if (isFinishing || isDestroyed) return@settings
            channels = root?.optJSONObject("live")?.optJSONArray("channels")
            if (channels == null && error != null && wall == null) status.text = error
            done()
        }
        api.prefs(profileId) { p, _ ->
            if (isFinishing || isDestroyed) return@prefs
            val live = p?.optJSONObject("live")
            if (wall == null) {
                only = ids(live?.optJSONArray("only"))
                order = ids(live?.optJSONArray("order"))
            }
            done()
        }
        // Three answers, one repaint; keep the counter honest when the wall route is missing.
        pending = 3
    }

    private fun ids(arr: JSONArray?): MutableList<String> {
        val out = ArrayList<String>()
        if (arr == null) return out
        for (i in 0 until arr.length()) {
            val v = arr.optString(i, "")
            if (v.isNotBlank() && v !in out) out.add(v)
        }
        return out
    }

    /** One channel on this screen, whichever hub version supplied it. */
    private data class Entry(
        val identity: String,
        val name: String,
        val group: String,
        val logo: String,
        val number: Int,
        val health: String,
        val error: String,
        val now: String,
    )

    /** The channels this screen can show, in the order the hub says the remote walks them. */
    private fun entries(): List<Entry> {
        val w = wall
        if (w != null) {
            return w.channels.map {
                Entry(it.identity, it.name, it.group, it.logo, it.number, it.health, it.error, it.now)
            }
        }
        val src = channels ?: return emptyList()
        val out = ArrayList<Entry>(src.length())
        for (i in 0 until src.length()) {
            val ch = src.optJSONObject(i) ?: continue
            val id = ch.optString("identity")
            if (id.isBlank() || ch.optBoolean("enabled", true).not()) continue
            out.add(
                Entry(
                    identity = id,
                    name = ch.optString("name"),
                    group = ch.optString("group"),
                    logo = ch.optString("logo"),
                    number = ch.optInt("number", 0),
                    health = ch.optString("health", "unknown"),
                    error = ch.optString("error"),
                    now = ch.optJSONObject("now")?.optString("title").orEmpty(),
                )
            )
        }
        return out
    }

    private fun byId(list: List<Entry>): Map<String, Entry> = list.associateBy { it.identity }

    /** The lineup as it can actually be drawn: ids the hub still offers, in the profile's order. */
    private fun lineupOf(list: List<Entry>): List<Entry> {
        val map = byId(list)
        return order.mapNotNull { map[it] }
    }

    private fun filtered(list: List<Entry>): List<Entry> {
        if (filter.isBlank()) return list
        val needle = filter.lowercase()
        // Match the name or the genre: "news" narrowing 383 tiles to 22 is the whole point.
        return list.filter { (it.name + " " + it.group).lowercase().contains(needle) }
    }

    private fun paint(focusAt: Int = -1, focusId: String? = null) {
        val all = entries()
        if (all.isEmpty()) {
            val why = wall?.wallError.orEmpty()
            status.text = when {
                why.isNotBlank() -> "The hub could not read its channels: $why"
                channels == null && wall == null -> "The hub sent no channels"
                else -> "No channels are available to this profile"
            }
            grid.adapter = null
            return
        }
        if (mode == Mode.LINEUP) {
            grid.layoutManager = LinearLayoutManager(this)
            grid.adapter = LineupAdapter(lineupOf(all))
            paintLineupStatus(all)
        } else {
            grid.layoutManager = GridLayoutManager(this, 6)
            grid.adapter = WallAdapter(filtered(all))
            paintWallStatus(all)
        }
        val at = when {
            focusId != null -> {
                val list = if (mode == Mode.LINEUP) lineupOf(all) else filtered(all)
                list.indexOfFirst { it.identity == focusId }.coerceAtLeast(0)
            }
            focusAt >= 0 -> focusAt
            else -> 0
        }
        grid.postDelayed({
            if (isFinishing || isDestroyed) return@postDelayed
            if (at > 0) grid.scrollToPosition(at)
            (grid.findViewHolderForAdapterPosition(at)?.itemView ?: grid.getChildAt(0))?.requestFocus()
        }, 120)
    }

    private fun lineupOn() = only.isNotEmpty()

    /** One honest sentence about the state of the air, from the hub's own probe verdicts. */
    private fun tally(all: List<Entry>): String {
        val working = all.count { it.health == "working" }
        val blocked = all.count { it.health == "blocked" }
        val dead = all.count { it.health == "dead" }
        val unknown = all.size - working - blocked - dead
        val parts = ArrayList<String>()
        if (working > 0) parts.add("$working on air")
        if (blocked > 0) parts.add("$blocked need a key from the channel")
        if (dead > 0) parts.add("$dead off the air")
        if (unknown > 0) parts.add("$unknown not checked yet")
        return parts.joinToString(" · ")
    }

    private fun paintWallStatus(all: List<Entry>) {
        val lineup = lineupOf(all)
        when {
            filter.isNotBlank() ->
                status.text = "$filter matches ${filtered(all).size} channels · press Search to change it"
            lineup.isEmpty() -> status.text =
                "${all.size} channels this profile may watch (${tally(all)}) · press My lineup to choose your own order"
            lineupOn() -> status.text =
                "Your lineup is on: the remote walks ${chan(lineup.size)} · ${all.size - lineup.size} others " +
                    "are off Live TV for this profile"
            else -> status.text =
                "Your ${chan(lineup.size)} lead the wall · ${all.size - lineup.size} others follow"
        }
    }

    private fun paintLineupStatus(all: List<Entry>) {
        val lineup = lineupOf(all)
        status.text = when {
            lineup.isEmpty() -> "No lineup yet · add channels from All channels, or press Add channels below"
            lineupOn() -> "${chan(lineup.size)} in the lineup · the remote walks ${
                if (lineup.size == 1) "it" else "them"
            } in this order"
            else -> "${chan(lineup.size)} first, then the other ${all.size - lineup.size} · " +
                "press the switch above to watch only your lineup"
        }
    }

    /** "1 channel · 3 channels" — the tally sentences must not say "1 channels". */
    private fun chan(n: Int): String = "$n ${if (n == 1) "channel" else "channels"}"

    private fun tune(e: Entry) {
        startActivity(
            Intent(this, PlayerActivity::class.java)
                .putExtra(PlayerActivity.EXTRA_PROFILE, profileId)
                .putExtra(PlayerActivity.EXTRA_IDENTITY, e.identity)
                .putExtra(PlayerActivity.EXTRA_TYPE, "channel")
                .putExtra(PlayerActivity.EXTRA_TITLE, e.name)
                .putExtra(PlayerActivity.EXTRA_POSTER, e.logo)
        )
    }

    // ---- the lineup ---------------------------------------------------------------------------

    /** Adds a channel to the lineup, at the end, and keeps the profile's order the record of it. */
    private fun addChannel(id: String) {
        if (id !in order) order.add(id)
        // `only` is the switch: when it has entries the wall and the zap ring are the lineup alone.
        // A profile that has never switched it on keeps watching everything, with its favourites first.
        if (lineupOn() && id !in only) only.add(id)
        save(focusId = id)
    }

    private fun removeChannel(id: String) {
        order.remove(id)
        only.remove(id)
        save(focusId = order.getOrElse(order.indexOf(id) - 1) { null } ?: order.lastOrNull())
    }

    /**
     * Move a channel in the order. `delta` is -1 (earlier) or +1 (later); this is what the remote's
     * LEFT/RIGHT keys do on a highlighted channel, which is why it saves immediately — the viewer
     * should never wonder whether a move they just made was kept.
     */
    private fun moveChannel(id: String, delta: Int) {
        val at = order.indexOf(id)
        if (at < 0) return
        val to = (at + delta).coerceIn(0, order.size - 1)
        if (to == at) {
            status.text = if (delta < 0) "Already the first channel in your lineup"
                else "Already the last channel in your lineup"
            return
        }
        val moved = order.toMutableList()
        moved.removeAt(at)
        moved.add(to, id)
        order = moved
        if (lineupOn()) only = order.filter { it in only }.toMutableList()
        save(focusId = id)
        status.text = "${byId(entries())[id]?.name ?: "Channel"} is now ${to + 1} in your lineup"
    }

    private fun setLineupOnly(on: Boolean) {
        only = if (on) order.toMutableList() else ArrayList()
        save()
        status.text = if (on) "Live TV and the remote now walk only your ${chan(order.size)}"
            else "Live TV shows every channel again, with your ${order.size} first"
    }

    private fun save(focusId: String? = null) {
        status.text = "Saving…"
        val live = JSONObject()
            .put("only", JSONArray(only))
            .put("order", JSONArray(order))
        api.savePrefs(profileId, JSONObject().put("live", live)) { p, error ->
            if (isFinishing || isDestroyed) return@savePrefs
            if (error != null) {
                status.text = error
                return@savePrefs
            }
            val l = p?.optJSONObject("live")
            if (wall == null) {
                only = ids(l?.optJSONArray("only"))
                order = ids(l?.optJSONArray("order"))
            }
            if (mode == Mode.LINEUP) {
                // Keep the viewer on the channel they moved: a list that jumps back to the top on every
                // change cannot be reordered at all.
                paint(focusId = focusId)
            } else {
                paint()
            }
            status.postDelayed({ if (!isFinishing) status.text = "" }, 2500)
        }
    }

    // ---- the lineup list ----------------------------------------------------------------------

    /**
     * One row per channel in the lineup, in the lineup's order. The row is the tool: LEFT/RIGHT move it
     * in the order, OK tunes it, holding OK takes it out or sends it to the top. A settings-style plate,
     * because a lineup is read as a list, not as artwork.
     */
    private inner class LineupAdapter(val items: List<Entry>) : RecyclerView.Adapter<LineupHolder>() {
        private val theme = app.theme
        private val radius = Screens.listRadius(app)

        override fun getItemCount(): Int = items.size + HEADER_ROWS

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LineupHolder {
            val row = LinearLayout(this@LiveChannelsActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = Screens.listHeight(app)
                setPadding(theme.px(24f), theme.px(14f), theme.px(22f), theme.px(14f))
                background = Screens.rowFill(app)
                isFocusable = true
                isClickable = true
            }
            val holder = LineupHolder(row)
            row.addView(holder.place, LinearLayout.LayoutParams(theme.px(56f), theme.px(34f)))
            val texts = LinearLayout(this@LiveChannelsActivity).apply { orientation = LinearLayout.VERTICAL }
            holder.label = Views.text(app, "li", 24f, app.theme.ink, "", bold = true, maxLines = 1)
            texts.addView(holder.label!!)
            holder.note = Views.text(app, "meta", 16f, app.theme.ink3, "", maxLines = 1).apply {
                setPadding(0, theme.px(3f), 0, 0)
            }
            texts.addView(holder.note!!)
            row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            holder.value = Views.text(app, "liMeta", 20f, app.theme.ink2, "", maxLines = 1, gravity = Gravity.END)
            row.addView(holder.value!!, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { leftMargin = theme.px(20f) })
            Focus.attach(row, row, app, radius, scale = false, elevate = false,
                onClick = {
                    when {
                        holder.header == Header.TOGGLE -> setLineupOnly(!lineupOn())
                        holder.header == Header.ADD -> setMode(Mode.WALL)
                        else -> holder.item?.let { tune(it) }
                    }
                },
                arrow = arrow@{ key ->
                    if (holder.item == null) return@arrow false
                    when (key) {
                        KeyEvent.KEYCODE_DPAD_LEFT -> { moveChannel(holder.item!!.identity, -1); true }
                        KeyEvent.KEYCODE_DPAD_RIGHT -> { moveChannel(holder.item!!.identity, 1); true }
                        else -> false
                    }
                },
                onFocus = { has ->
                    row.background = if (has) focusedPlate() else Screens.rowFill(app)
                    holder.label?.setTextColor(if (has) wallInk else app.theme.ink)
                },
            )
            row.setOnLongClickListener {
                holder.item?.let { showLineupActions(it) }
                true
            }
            return holder
        }

        override fun onBindViewHolder(holder: LineupHolder, position: Int) {
            when (position) {
                0 -> bindHeader(holder, Header.TOGGLE)
                1 -> bindHeader(holder, Header.ADD)
                else -> bindChannel(holder, items[position - HEADER_ROWS], position - HEADER_ROWS)
            }
        }

        private fun bindHeader(holder: LineupHolder, which: Header) {
            holder.header = which
            holder.item = null
            when (which) {
                Header.TOGGLE -> {
                    holder.place.text = ""
                    holder.label?.text = "Watch only my lineup"
                    holder.note?.text = "On: the wall and the remote walk these channels alone"
                    holder.value?.text = if (lineupOn()) "On" else "Off"
                }
                Header.ADD -> {
                    holder.place.text = "+"
                    holder.label?.text = "Add channels"
                    holder.note?.text = "Open the whole list and pick what belongs here"
                    holder.value?.text = "${wall?.unlisted ?: 0} not in the lineup"
                }
            }
        }

        private fun bindChannel(holder: LineupHolder, e: Entry, at: Int) {
            holder.header = null
            holder.item = e
            holder.place.text = (at + 1).toString()
            holder.label?.text = e.name
            val extra = ArrayList<String>()
            if (e.group.isNotBlank()) extra.add(e.group)
            if (e.now.isNotBlank()) extra.add("Now: ${e.now}")
            val verdict = healthWord(e)
            if (verdict.isNotBlank()) extra.add(verdict)
            holder.note?.text = extra.joinToString(" · ")
            // The D-pad mapping is printed where the hand already is, because a viewer cannot guess that
            // left and right are the reorder keys rather than the page's own navigation.
            holder.value?.text = "◀  ▶"
        }
    }

    private fun focusedPlate(): android.graphics.drawable.Drawable = Views.rounded(
        app.theme.colorOr("listFillFocused", app.theme.surface2),
        Screens.listRadius(app),
        maxOf(2, app.theme.px(2f)),
        app.theme.focusRingColor,
    )

    /** What the hub's own probe last said about this channel, in words a viewer can act on. */
    private fun healthWord(e: Entry): String = when {
        e.health == "working" -> ""
        e.health == "blocked" -> "blocked by the channel"
        e.health == "dead" -> "off the air when last checked"
        else -> ""
    }

    private enum class Header { TOGGLE, ADD }

    private inner class LineupHolder(val row: LinearLayout) : RecyclerView.ViewHolder(row) {
        val place = Views.text(app, "meta", 19f, app.theme.ink2, "", bold = true, gravity = Gravity.CENTER)
        var label: TextView? = null
        var note: TextView? = null
        var value: TextView? = null
        var item: Entry? = null
        var header: Header? = null

        init {
            // `place` is styled here and laid out by the adapter's onCreateViewHolder — adding it in both
            // places is the "child already has a parent" crash.
            place.background = Views.rounded(Color.argb(185, 8, 8, 8), app.theme.px(5f))
        }
    }

    private fun showLineupActions(e: Entry) {
        val at = order.indexOf(e.identity)
        val options = ArrayList<Pair<String, String>>()
        options.add("tune" to "Watch ${e.name}")
        if (at > 0) options.add("top" to "Move ${e.name} to the top")
        if (at > 0) options.add("earlier" to "Move ${e.name} earlier")
        if (at in 0 until order.size - 1) options.add("later" to "Move ${e.name} later")
        options.add("remove" to "Take ${e.name} out of the lineup")
        options.add("back" to "Back")
        showPicker(e.name, options) { action ->
            when (action) {
                "tune" -> tune(e)
                "remove" -> removeChannel(e.identity)
                "earlier" -> moveChannel(e.identity, -1)
                "later" -> moveChannel(e.identity, 1)
                "top" -> {
                    val moved = order.toMutableList()
                    moved.remove(e.identity)
                    moved.add(0, e.identity)
                    order = moved
                    if (lineupOn()) only = order.filter { it in only }.toMutableList()
                    save(focusId = e.identity)
                }
            }
        }
    }

    // ---- the wall -----------------------------------------------------------------------------

    /** The wall: one tile per channel, logo above the name, OK tunes it, hold OK edits the lineup. */
    private inner class WallAdapter(val items: List<Entry>) : RecyclerView.Adapter<WallHolder>() {
        private val theme = app.theme
        private val artH = theme.px(190f)
        private val captionH = theme.px(86f)
        private val radius = theme.radius("card", 6f)

        override fun getItemCount(): Int = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): WallHolder {
            val card = FrameLayout(this@LiveChannelsActivity)
            card.layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, artH + captionH
            ).apply {
                leftMargin = theme.px(13f); rightMargin = theme.px(13f)
                bottomMargin = theme.px(30f)
            }
            card.isFocusable = true
            card.isClickable = true
            // The focus treatment is a background halo, not a foreground border over the logo.
            val glowPad = theme.focusGlowInset
            val ringOn = View(this@LiveChannelsActivity)
            card.clipChildren = false
            card.addView(ringOn, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, artH + glowPad * 2
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                leftMargin = -glowPad; rightMargin = -glowPad; topMargin = -glowPad
            })
            val plate = FrameLayout(this@LiveChannelsActivity).apply {
                background = Views.rounded(theme.surface, radius, theme.px(1f), theme.line)
            }
            card.addView(plate, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, artH
            ).apply { gravity = Gravity.TOP })
            val logo = ImageView(this@LiveChannelsActivity).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                setPadding(theme.px(22f), theme.px(22f), theme.px(22f), theme.px(22f))
            }
            plate.addView(logo, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, artH
            ))
            val mark = Views.text(app, "h1", 34f, theme.ink2, "", bold = true, gravity = Gravity.CENTER)
            plate.addView(mark, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, artH
            ).apply { gravity = Gravity.CENTER })
            val num = Views.text(app, "meta", 13f, theme.ink3, "", bold = true, maxLines = 1)
            num.setPadding(theme.px(8f), theme.px(3f), theme.px(8f), theme.px(3f))
            num.background = Views.rounded(Color.argb(185, 8, 8, 8), theme.px(5f))
            plate.addView(num, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                leftMargin = theme.px(10f); topMargin = theme.px(10f)
            })
            val name = Views.text(app, "caption", 18f, wallInk, "", bold = true, maxLines = 1)
            name.setPadding(theme.px(4f), theme.px(12f), theme.px(4f), 0)
            card.addView(name, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, captionH
            ).apply { gravity = Gravity.BOTTOM })
            val sub = Views.text(app, "meta", 14f, wallInk2, "", maxLines = 1)
            sub.setPadding(theme.px(4f), 0, theme.px(4f), theme.px(4f))
            card.addView(sub, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.BOTTOM; bottomMargin = theme.px(4f) })
            val holder = WallHolder(card, plate, logo, mark, num, name, sub)
            Focus.attach(card, ringOn, app, radius, animateScale = false, elevate = false,
                glowInset = glowPad,
                onClick = { holder.item?.let { tune(it) } })
            card.setOnLongClickListener {
                holder.item?.let { showActions(it) }
                true
            }
            return holder
        }

        override fun onBindViewHolder(holder: WallHolder, position: Int) {
            val e = items[position]
            holder.item = e
            holder.name.text = e.name
            holder.num.text = if (e.number > 0) e.number.toString() else ""
            holder.num.visibility = if (e.number > 0) View.VISIBLE else View.GONE
            val extra = ArrayList<String>()
            if (e.now.isNotBlank()) extra.add("Now: ${e.now}")
            if (e.group.isNotBlank()) extra.add(e.group)
            val verdict = healthWord(e)
            if (verdict.isNotBlank()) extra.add(verdict)
            if (e.identity in order) extra.add("In lineup")
            holder.sub.text = extra.joinToString(" · ")
            if (e.logo.isNotBlank()) {
                holder.mark.visibility = View.GONE
                holder.logo.visibility = View.VISIBLE
                BrowseImages.load(holder.logo, api.absolute(e.logo), app.theme.px(320f)) { ok ->
                    if (isFinishing || isDestroyed) return@load
                    holder.logo.visibility = if (ok) View.VISIBLE else View.GONE
                    if (!ok) {
                        holder.mark.visibility = View.VISIBLE
                        holder.mark.text = initials(e.name)
                    }
                }
            } else {
                holder.logo.visibility = View.GONE
                holder.mark.visibility = View.VISIBLE
                holder.mark.text = initials(e.name)
            }
        }

        private fun initials(name: String): String =
            name.split(" ").filter { it.isNotBlank() }.take(2).map { it.first().uppercaseChar() }.joinToString("")
    }

    private inner class WallHolder(
        view: View, val plate: FrameLayout, val logo: ImageView, val mark: TextView,
        val num: TextView, val name: TextView, val sub: TextView,
    ) : RecyclerView.ViewHolder(view) {
        var item: Entry? = null
    }

    private fun showActions(e: Entry) {
        val inLineup = e.identity in order
        val options = ArrayList<Pair<String, String>>()
        options.add("tune" to "Watch ${e.name}")
        if (inLineup) {
            options.add("remove" to "Take ${e.name} out of my lineup")
            options.add("lineup" to "Open my lineup to order it")
        } else {
            options.add("add" to "Add ${e.name} to my lineup")
        }
        options.add("back" to "Back")
        showPicker(e.name, options) { action ->
            when (action) {
                "tune" -> tune(e)
                "add" -> { addChannel(e.identity); paint(focusId = e.identity) }
                "remove" -> { removeChannel(e.identity); paint(focusId = e.identity) }
                "lineup" -> setMode(Mode.LINEUP)
            }
        }
    }

    private fun showPicker(title: String, options: List<Pair<String, String>>, onPick: (String) -> Unit) {
        if (picker == null) pickerReturn = currentFocus
        closePicker()
        val theme = app.theme
        pickerReturn = currentFocus ?: pickerReturn
        val layer = FrameLayout(this).apply { setBackgroundColor(Screens.dimColor(app)) }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(theme.px(26f), theme.px(20f), theme.px(26f), theme.px(20f))
            background = Screens.modalFill(app)
        }
        panel.addView(Views.text(app, "li", 24f, theme.ink, title, bold = true, maxLines = 2))
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        var first: View? = null
        for ((value, label) in options) {
            val row = Screens.pickerRow(this, app, label, selected = false) {
                closePicker()
                onPick(value)
            }
            list.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, theme.px(56f)))
            if (first == null) first = row
        }
        panel.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = theme.px(8f)
        })
        layer.addView(panel, FrameLayout.LayoutParams(theme.px(640f), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER
        })
        addContentView(layer, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        picker = layer
        first?.post { first?.requestFocus() }
    }

    private fun closePicker() {
        picker?.let { (it.parent as? ViewGroup)?.removeView(it) }
        val wasOpen = picker != null
        picker = null
        if (wasOpen) {
            pickerReturn?.takeIf { it.isAttachedToWindow }?.requestFocus()
            pickerReturn = null
        }
    }

    override fun onBackPressed() {
        if (picker != null) { closePicker(); return }
        super.onBackPressed()
    }

    companion object {
        const val EXTRA_PROFILE = "profile"
        private const val REQ_FILTER = 71
        /** The two tool rows above the lineup: the watch-only switch and the way to add channels. */
        private const val HEADER_ROWS = 2
    }
}
