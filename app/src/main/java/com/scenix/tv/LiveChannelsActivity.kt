package com.scenix.tv

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
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
 * tunes one — the same Player the Home Live row uses. Holding OK on a tile opens the lineup editor,
 * where the profile adds the channels it wants, removes them, and puts them in its own order.
 *
 * The channel catalog comes from /app/v1/settings (`live.channels`), the same list the web's channel
 * manager uses; the lineup is written to the profile's prefs (`live.only` / `live.order`), never to the
 * household's settings. With no lineup the wall shows every channel the rules allow, so the tab is
 * never an empty room.
 */
class LiveChannelsActivity : Activity() {

    private lateinit var app: AppContext
    private lateinit var profileId: String
    private lateinit var grid: RecyclerView
    private lateinit var status: TextView
    private var channels: JSONArray? = null
    private var only: MutableList<String> = ArrayList()
    private var order: MutableList<String> = ArrayList()
    private var picker: View? = null
    // The row that opened the last picker; it takes focus back when the picker
    // closes, so the remote never lands on the page's back control by accident.
    private var pickerReturn: View? = null
    private val api by lazy { BrowseApi(applicationContext, (application as ScenixApp).prefs) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        app = (application as ScenixApp).ctx
        profileId = intent?.getStringExtra(EXTRA_PROFILE).orEmpty()
        if (profileId.isBlank()) { finish(); return }

        val theme = app.theme
        val root = FrameLayout(this).apply {
            val wall = Screens.wall(app)
            if (wall != null) background = wall else setBackgroundColor(theme.bg)
        }
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
        status = Views.text(app, "sub", 21f, theme.ink2, "Reading channels…", gravity = Gravity.CENTER)
        root.addView(status, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, theme.px(64f)
        ).apply { gravity = Gravity.BOTTOM })
        setContentView(root)
        Motion.enter(app.theme, root)
        load()
    }

    /** A floating bar over the wall: a feather chevron home, and the tab's name. */
    private fun topBar(): View {
        val theme = app.theme
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(theme.gutter, theme.px(10f), theme.gutter, 0)
        }
        val back = ImageView(this).apply {
            setImageResource(R.drawable.ic_chevron_left)
            setColorFilter(theme.ink, android.graphics.PorterDuff.Mode.SRC_IN)
            scaleType = ImageView.ScaleType.FIT_CENTER
            isFocusable = true
            isClickable = true
            setOnClickListener { finish() }
        }
        Focus.attach(back, back, app, theme.px(9f), scale = false, elevate = false)
        bar.addView(back, LinearLayout.LayoutParams(theme.px(34f), theme.px(34f)))
        bar.addView(Views.text(app, "rowTitle", 26f, theme.ink, "Live TV", bold = true).apply {
            setPadding(theme.px(18f), 0, 0, 0)
        })
        return bar
    }

    private fun load() {
        status.text = "Reading channels…"
        var pending = 2
        fun done() {
            pending--
            if (pending == 0) { status.text = ""; paint() }
        }
        api.settings { root, error ->
            if (isFinishing || isDestroyed) return@settings
            channels = root?.optJSONObject("live")?.optJSONArray("channels")
            if (channels == null && error != null) status.text = error
            done()
        }
        api.prefs(profileId) { p, _ ->
            if (isFinishing || isDestroyed) return@prefs
            val live = p?.optJSONObject("live")
            only = ids(live?.optJSONArray("only"))
            order = ids(live?.optJSONArray("order"))
            done()
        }
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

    private fun paint() {
        val src = channels ?: return
        val list = ArrayList<JSONObject>()
        for (i in 0 until src.length()) {
            val ch = src.optJSONObject(i) ?: continue
            if (ch.optString("identity").isBlank()) continue
            if (ch.optBoolean("enabled", true).not()) continue
            list.add(ch)
        }
        if (list.isEmpty()) {
            status.text = "No channels are available to this profile"
            return
        }
        // A personal lineup leads the wall, in its own order; the rest follow so the tab is never empty.
        val chosen = ArrayList<JSONObject>()
        val rest = ArrayList<JSONObject>()
        val byId = list.associateBy { it.optString("identity") }
        for (id in order) if (id in only) byId[id]?.let { chosen.add(it) }
        for (ch in list) if (ch.optString("identity") !in only) rest.add(ch)
        grid.adapter = WallAdapter(chosen + rest)
        if (only.isEmpty()) {
            status.text = "Every channel this profile may watch · hold OK on a tile for the lineup"
        }
        grid.postDelayed({
            (grid.findViewHolderForAdapterPosition(0)?.itemView ?: grid.getChildAt(0))?.requestFocus()
        }, 150)
    }

    private fun tune(ch: JSONObject) {
        startActivity(
            Intent(this, PlayerActivity::class.java)
                .putExtra(PlayerActivity.EXTRA_PROFILE, profileId)
                .putExtra(PlayerActivity.EXTRA_IDENTITY, ch.optString("identity"))
                .putExtra(PlayerActivity.EXTRA_TYPE, "channel")
                .putExtra(PlayerActivity.EXTRA_TITLE, ch.optString("name"))
                .putExtra(PlayerActivity.EXTRA_POSTER, ch.optString("logo"))
        )
    }

    /** The wall: one tile per channel, logo above the name, OK tunes it, hold OK edits the lineup. */
    private inner class WallAdapter(val items: List<JSONObject>) : RecyclerView.Adapter<WallHolder>() {
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
            val name = Views.text(app, "caption", 18f, theme.ink, "", bold = true, maxLines = 1)
            name.setPadding(theme.px(4f), theme.px(12f), theme.px(4f), 0)
            card.addView(name, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, captionH
            ).apply { gravity = Gravity.BOTTOM })
            val sub = Views.text(app, "meta", 14f, theme.ink3, "", maxLines = 1)
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
            val ch = items[position]
            holder.item = ch
            val name = ch.optString("name")
            holder.name.text = name
            val number = ch.optInt("number", 0)
            holder.num.text = if (number > 0) number.toString() else ""
            holder.num.visibility = if (number > 0) View.VISIBLE else View.GONE
            val group = ch.optString("group")
            val inLineup = ch.optString("identity") in only
            holder.sub.text = listOf(group, if (inLineup) "In lineup" else "").filter { it.isNotBlank() }
                .joinToString(" · ")
            val logoUrl = ch.optString("logo")
            if (logoUrl.isNotBlank()) {
                holder.mark.visibility = View.GONE
                holder.logo.visibility = View.VISIBLE
                BrowseImages.load(holder.logo, api.absolute(logoUrl), theme.px(320f)) { ok ->
                    if (isFinishing || isDestroyed) return@load
                    holder.logo.visibility = if (ok) View.VISIBLE else View.GONE
                    if (!ok) {
                        holder.mark.visibility = View.VISIBLE
                        holder.mark.text = initials(name)
                    }
                }
            } else {
                holder.logo.visibility = View.GONE
                holder.mark.visibility = View.VISIBLE
                holder.mark.text = initials(name)
            }
        }

        private fun initials(name: String): String =
            name.split(" ").filter { it.isNotBlank() }.take(2).map { it.first().uppercaseChar() }.joinToString("")
    }

    private inner class WallHolder(
        view: View, val plate: FrameLayout, val logo: ImageView, val mark: TextView,
        val num: TextView, val name: TextView, val sub: TextView,
    ) : RecyclerView.ViewHolder(view) {
        var item: JSONObject? = null
    }

    private fun showActions(ch: JSONObject) {
        val id = ch.optString("identity")
        val name = ch.optString("name").ifBlank { id }
        val added = id in only
        val options = ArrayList<Pair<String, String>>()
        options.add("tune" to "Watch $name")
        if (added) {
            options.add("remove" to "Remove $name from my lineup")
            val at = order.indexOf(id)
            if (at > 0) options.add("earlier" to "Move $name earlier")
            if (at in 0 until order.size - 1) options.add("later" to "Move $name later")
        } else {
            options.add("add" to "Add $name to my lineup")
        }
        options.add("back" to "Back")
        showPicker(name, options) { action ->
            when (action) {
                "tune" -> tune(ch)
                "add" -> {
                    if (id !in only) only.add(id)
                    if (id !in order) order.add(id)
                    save()
                }
                "remove" -> {
                    only.remove(id)
                    order.remove(id)
                    save()
                }
                "earlier", "later" -> {
                    val at = order.indexOf(id)
                    if (at >= 0) {
                        val to = order.toMutableList()
                        to.removeAt(at)
                        to.add(if (action == "earlier") (at - 1).coerceAtLeast(0) else (at + 1).coerceAtMost(to.size), id)
                        order = to
                        only = to.filter { it in only }.toMutableList()
                        save()
                    }
                }
            }
        }
    }

    private fun save() {
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
            only = ids(l?.optJSONArray("only"))
            order = ids(l?.optJSONArray("order"))
            status.text = "Saved"
            paint()
            status.postDelayed({ if (!isFinishing) status.text = "" }, 2500)
        }
    }

    private fun showPicker(title: String, options: List<Pair<String, String>>, onPick: (String) -> Unit) {
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
        picker = null
        pickerReturn?.requestFocus()
        pickerReturn = null
    }

    override fun onBackPressed() {
        if (picker != null) { closePicker(); return }
        super.onBackPressed()
    }

    companion object {
        const val EXTRA_PROFILE = "profile"
    }
}
