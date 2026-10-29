package com.scenix.tv

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
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
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.scenix.tv.core.AppContext
import com.scenix.tv.core.Focus
import com.scenix.tv.core.Item
import com.scenix.tv.core.Views

/**
 * Stage one of the native front end: the Home/browse shelves.
 *
 * The hub still owns the catalogue and the web UI still owns Detail, Player and Settings. This
 * activity renders the same `/app/v1/home` rows in a native vertical RecyclerView of horizontal
 * RecyclerViews, so a D-pad walk reuses card views and scrolls a compositor layer instead of
 * repainting a 262-card page. A tile opens the web Detail route; the web Home action comes back here.
 */
class BrowseActivity : Activity() {

    private lateinit var app: AppContext
    private lateinit var profileId: String
    private lateinit var list: RecyclerView
    private lateinit var status: TextView
    private val api by lazy { BrowseApi(applicationContext, (application as ScenixApp).prefs) }
    private var loaded = false
    private var stage: Stage? = null
    private var heroItem: Item? = null

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        val next = intent?.getStringExtra(EXTRA_PROFILE).orEmpty()
        if (next.isNotBlank() && next != profileId) {
            profileId = next
            load()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val sc = application as ScenixApp
        app = sc.ctx
        profileId = intent?.getStringExtra(EXTRA_PROFILE).orEmpty()
        if (profileId.isBlank()) {
            // Never guess who is watching. The web profile gate is the one place a PIN is checked.
            startActivity(Intent(this, WebShellActivity::class.java)
                .putExtra(WebShellActivity.EXTRA_URL, WebShellActivity.shellUrl(this, "#/profiles")))
            finish()
            return
        }

        // Apple's scene pack puts one stage behind the whole screen: the shelves float on it, and the
        // stage crossfades to whatever tile holds focus. Other packs keep the opaque page they had.
        val stageMode = app.theme.packString("stage", "off") == "focus"
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(if (stageMode) Color.TRANSPARENT else app.theme.bg)
        }
        val root: View = if (stageMode) {
            val frame = FrameLayout(this).apply { setBackgroundColor(app.theme.bg) }
            val s = Stage()
            stage = s
            frame.addView(s.frame, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            ))
            frame.addView(content, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            ))
            frame
        } else content

        content.addView(topBar(stageMode), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, app.theme.px(96f)
        ))
        list = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@BrowseActivity)
            setHasFixedSize(true)
            setItemViewCacheSize(24)
            setBackgroundColor(if (stageMode) Color.TRANSPARENT else app.theme.bg)
            itemAnimator = null
            clipToPadding = false
            setPadding(0, app.theme.px(16f), 0, app.theme.px(64f))
        }
        content.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        status = Views.text(app, "sub", 24f, app.theme.ink2, "Loading the shelves…", gravity = Gravity.CENTER)
        content.addView(status, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, app.theme.px(80f)))
        setContentView(root)
        load()
    }

    /**
     * The Apple scene stage: one full-screen backdrop for the whole screen, crossfading to the tile
     * under the ring and dimming/blurring while the viewer walks the shelves. Two ImageViews are
     * enough for a crossfade; `front` is whichever one is currently opaque.
     */
    private inner class Stage {
        val frame = FrameLayout(this@BrowseActivity)
        private val a = ImageView(this@BrowseActivity).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            alpha = 0f
        }
        private val b = ImageView(this@BrowseActivity).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            alpha = 0f
        }
        private val veil = View(this@BrowseActivity).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.argb(120, 0, 0, 0), Color.argb(30, 0, 0, 0), Color.argb(215, 0, 0, 0))
            )
            alpha = 0.84f
        }
        private var front = a
        private var current: String? = null

        init {
            frame.addView(a, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            ))
            frame.addView(b, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            ))
            frame.addView(veil, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            ))
        }

        fun show(url: String?) {
            val clean = url?.trim().orEmpty()
            if (clean.isEmpty() || clean == current) return
            current = clean
            val incoming = if (front === a) b else a
            incoming.animate().cancel()
            incoming.alpha = 0f
            BrowseImages.load(incoming, clean, app.theme.px(1400f)) { ok ->
                if (isFinishing || isDestroyed) return@load
                if (!ok || current != clean) return@load
                incoming.animate().alpha(1f).setDuration(650).withEndAction {
                    front.alpha = 0f
                    front = incoming
                }.start()
            }
        }

        fun setRoaming(roam: Boolean) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val effect = if (roam) RenderEffect.createBlurEffect(24f, 24f, Shader.TileMode.CLAMP) else null
                a.setRenderEffect(effect)
                b.setRenderEffect(effect)
            }
            veil.animate().alpha(if (roam) 1f else 0.84f).setDuration(350).start()
        }
    }

    /** Every mounted card in every visible rail, for the stage's "everything but the ring dims" pass. */
    private fun eachCard(block: (View) -> Unit) {
        for (i in 0 until list.childCount) {
            val shelf = list.getChildAt(i)
            if (shelf !is ViewGroup) continue
            for (j in 0 until shelf.childCount) {
                val rail = shelf.getChildAt(j)
                if (rail is RecyclerView) {
                    for (k in 0 until rail.childCount) block(rail.getChildAt(k))
                }
            }
        }
    }

    private fun onCardFocused(card: View, item: Item) {
        val s = stage ?: return
        s.show(item.backdrop ?: item.poster ?: item.logo)
        s.setRoaming(true)
        eachCard { it.alpha = if (it === card) 1f else 0.58f }
    }

    private fun onChromeFocused() {
        val s = stage ?: return
        s.setRoaming(false)
        s.show(heroItem?.backdrop ?: heroItem?.poster)
        eachCard { it.alpha = 1f }
    }

    private fun topBar(transparent: Boolean = false): View {
        val theme = app.theme
        val brand = Views.text(app, "logo", 30f, theme.brand, "SCENIX", bold = true)
        val profile = navButton("Profile") {
            startActivity(
                Intent(this@BrowseActivity, ProfilesActivity::class.java)
                    .putExtra(ProfilesActivity.EXTRA_FROM_BROWSE, true)
            )
        }
        val search = navButton("Search", theme.px(24f)) {
            startActivity(
                Intent(this@BrowseActivity, SearchActivity::class.java)
                    .putExtra(SearchActivity.EXTRA_PROFILE, profileId)
            )
        }
        val settings = navButton("Settings", theme.px(28f)) {
            startActivity(
                Intent(this@BrowseActivity, SettingsActivity::class.java)
                    .putExtra(SettingsActivity.EXTRA_PROFILE, profileId)
            )
        }
        if (theme.navStyle == "pill") {
            // Apple-style packs float one centred pill over the stage; the wordmark stays at the edge.
            val bar = FrameLayout(this)
            bar.setBackgroundColor(if (transparent) Color.TRANSPARENT else theme.bg)
            bar.addView(brand, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                leftMargin = theme.gutter
            })
            val pill = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(theme.px(10f), theme.px(6f), theme.px(10f), theme.px(6f))
                background = Views.rounded(theme.surface2, theme.px(999f), theme.px(1f), theme.line)
            }
            pill.addView(profile)
            pill.addView(search)
            pill.addView(settings)
            bar.addView(pill, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.CENTER })
            return bar
        }
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setPadding(theme.gutter, 0, theme.gutter, 0)
        bar.setBackgroundColor(if (transparent) Color.TRANSPARENT else theme.bg)
        bar.addView(brand, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        bar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        bar.addView(profile)
        bar.addView(search, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginStart = theme.px(24f)
        })
        bar.addView(settings, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginStart = theme.px(28f)
        })
        return bar
    }

    private fun navButton(label: String, left: Int = 0, action: () -> Unit): View {
        val b = Views.text(app, "li", 22f, app.theme.ink, label, bold = true, gravity = Gravity.CENTER)
        b.setPadding(app.theme.px(18f), app.theme.px(10f), app.theme.px(18f), app.theme.px(10f))
        b.isFocusable = true
        b.isClickable = true
        b.setOnClickListener { action() }
        Focus.attach(b, b, app, app.theme.radius("button", 6f), scale = false, elevate = false,
            onFocus = { if (it) onChromeFocused() })
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.marginStart = left
        b.layoutParams = lp
        return b
    }

    private fun load() {
        status.visibility = View.VISIBLE
        status.text = "Loading the shelves…"
        api.load(profileId) { rows, error ->
            if (isFinishing || isDestroyed) return@load
            if (rows == null) {
                loaded = false
                status.visibility = View.VISIBLE
                status.text = error ?: "The hub is not answering"
                return@load
            }
            loaded = true
            status.visibility = View.GONE
            val hero = rows.firstOrNull { it.id == "hero" }?.items?.firstOrNull()
            heroItem = hero
            val shelves = rows.filter { it.id != "hero" && it.items.isNotEmpty() }
            stage?.show(hero?.backdrop ?: hero?.poster
                ?: shelves.firstOrNull()?.items?.firstOrNull()?.backdrop)
            val adapter = BrowseAdapter(hero, shelves)
            list.adapter = adapter
            list.postDelayed({
                val first = adapter.heroPlay ?: firstFocusable(list) ?: return@postDelayed
                first.requestFocus()
            }, 700)
        }
    }

    private fun firstFocusable(parent: ViewGroup): View? {
        for (i in 0 until parent.childCount) {
            val c = parent.getChildAt(i)
            if (c.isFocusable && c.visibility == View.VISIBLE) return c
            if (c is ViewGroup) firstFocusable(c)?.let { return it }
        }
        return null
    }

    private fun openWeb(hash: String) {
        val route = "?profile=${Uri.encode(profileId)}$hash"
        startActivity(
            Intent(this, WebShellActivity::class.java)
                .putExtra(WebShellActivity.EXTRA_URL, WebShellActivity.shellUrl(this, route))
                .putExtra(WebShellActivity.EXTRA_FROM_BROWSE, true)
                .putExtra(WebShellActivity.EXTRA_PROFILE_HINT, profileId)
        )
    }

    private fun openDetail(item: Item) {
        startActivity(
            Intent(this, DetailActivity::class.java)
                .putExtra(DetailActivity.EXTRA_PROFILE, profileId)
                .putExtra(DetailActivity.EXTRA_IDENTITY, item.identity)
        )
    }

    private inner class BrowseAdapter(
        private val hero: Item?,
        private val shelves: List<BrowseRow>,
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        var heroPlay: View? = null
            private set

        override fun getItemCount(): Int = (if (hero != null) 1 else 0) + shelves.size

        override fun getItemViewType(position: Int): Int =
            if (hero != null && position == 0) TYPE_HERO else TYPE_SHELF

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val theme = app.theme
            return if (viewType == TYPE_HERO) {
                val slot = FrameLayout(this@BrowseActivity)
                val heroStyle = theme.heroStyle
                val split = heroStyle == "split"
                val centred = heroStyle == "centred" || heroStyle == "centered" || theme.heroAlign == "center"
                slot.layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    if (split) theme.heroHeightPx else theme.heroHeightPx.coerceAtLeast(theme.px(540f))
                )
                val art = ImageView(this@BrowseActivity).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setBackgroundColor(theme.surface2)
                }
                slot.addView(art, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                val scrim = View(this@BrowseActivity).apply {
                    background = if (split) GradientDrawable(
                        GradientDrawable.Orientation.LEFT_RIGHT,
                        intArrayOf(theme.bg, Color.TRANSPARENT)
                    ) else GradientDrawable(
                        GradientDrawable.Orientation.BOTTOM_TOP,
                        intArrayOf(theme.bg, Color.TRANSPARENT)
                    )
                }
                slot.addView(scrim, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                // Editorial packs put the poster in the frame and the words beside it; classic packs let
                // the backdrop fill the screen and float the words over it. The poster view exists in
                // both, hidden when the pack does not ask for it.
                val poster = ImageView(this@BrowseActivity).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setBackgroundColor(theme.surface2)
                    visibility = if (split) View.VISIBLE else View.GONE
                }
                if (split) {
                    slot.addView(poster, FrameLayout.LayoutParams(theme.px(300f), theme.px(450f)).apply {
                        gravity = Gravity.START or Gravity.CENTER_VERTICAL
                        leftMargin = theme.gutter
                    })
                }
                val words = LinearLayout(this@BrowseActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = when {
                        centred -> Gravity.CENTER
                        split -> Gravity.CENTER_VERTICAL or Gravity.START
                        else -> Gravity.BOTTOM or Gravity.START
                    }
                    setPadding(
                        if (split) theme.gutter + theme.px(340f) else theme.gutter,
                        theme.px(32f), theme.gutter, theme.px(44f)
                    )
                }
                val title = Views.text(app, "h1", 54f, theme.ink, "", bold = true,
                    gravity = if (centred) Gravity.CENTER else Gravity.START)
                val logo = ImageView(this@BrowseActivity).apply {
                    scaleType = if (centred) ImageView.ScaleType.FIT_CENTER else ImageView.ScaleType.FIT_START
                    visibility = View.GONE
                }
                val meta = Views.text(app, "sub", 22f, theme.ink2, "",
                    gravity = if (centred) Gravity.CENTER else Gravity.START)
                val play = Views.text(app, "button", 22f, theme.onBrand, "Play", bold = true, gravity = Gravity.CENTER)
                play.setPadding(theme.px(32f), theme.px(14f), theme.px(32f), theme.px(14f))
                play.background = Views.rounded(theme.brand, theme.radius("button", 6f))
                play.isFocusable = true
                play.isClickable = true
                Focus.attach(play, play, app, theme.radius("button", 6f), scale = false, elevate = false,
                    onFocus = { if (it) onChromeFocused() })
                words.addView(logo, LinearLayout.LayoutParams(theme.px(560f), theme.px(130f)).apply {
                    bottomMargin = theme.px(6f)
                    if (centred) gravity = Gravity.CENTER_HORIZONTAL
                })
                words.addView(title, LinearLayout.LayoutParams(
                    if (centred) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ))
                words.addView(meta, LinearLayout.LayoutParams(
                    if (centred) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = theme.px(10f)
                })
                words.addView(play, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = theme.px(22f)
                })
                slot.addView(words, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                HeroHolder(slot, art, poster, title, logo, meta, play, split)
            } else {
                val box = LinearLayout(this@BrowseActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    setPadding(0, theme.px(18f), 0, 0)
                }
                val head = Views.text(app, "rowTitle", 28f, theme.ink, "", bold = true)
                head.setPadding(theme.gutter, 0, theme.gutter, theme.px(12f))
                box.addView(head, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                val rail = RecyclerView(this@BrowseActivity).apply {
                    layoutManager = LinearLayoutManager(this@BrowseActivity, LinearLayoutManager.HORIZONTAL, false)
                    setHasFixedSize(true)
                    setItemViewCacheSize(16)
                    itemAnimator = null
                    isNestedScrollingEnabled = false
                    setPadding(theme.gutter, theme.px(18f), theme.gutter, theme.px(26f))
                    clipToPadding = false
                }
                box.addView(rail, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                ShelfHolder(box, head, rail)
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            if (holder is HeroHolder) {
                val item = hero ?: return
                holder.title.text = item.title
                holder.title.visibility = View.VISIBLE
                holder.logo.visibility = View.GONE
                val facts = ArrayList<String>()
                if (item.match > 0) facts.add("${item.match}% match")
                if (item.isNew) facts.add("New")
                if (item.year > 0) facts.add(item.year.toString())
                if (item.maturity.isNotBlank()) facts.add(item.maturity)
                item.genres.firstOrNull()?.takeIf { it.isNotBlank() }?.let { facts.add(it) }
                holder.meta.text = facts.joinToString(" · ")
                BrowseImages.load(holder.art,
                    if (holder.split) item.backdrop ?: item.poster else item.poster ?: item.backdrop,
                    app.theme.px(1100f))
                if (holder.split) BrowseImages.load(holder.poster, item.poster ?: item.backdrop, app.theme.px(420f))
                if (!item.logo.isNullOrBlank()) {
                    holder.logo.visibility = View.VISIBLE
                    BrowseImages.load(holder.logo, item.logo, app.theme.px(560f)) { ok ->
                        holder.logo.visibility = if (ok) View.VISIBLE else View.GONE
                        holder.title.visibility = if (ok) View.GONE else View.VISIBLE
                    }
                }
                heroPlay = holder.play
                holder.play.setOnClickListener { openDetail(item) }
                holder.itemView.setOnClickListener { openDetail(item) }
            } else if (holder is ShelfHolder) {
                val row = shelves[position - (if (hero != null) 1 else 0)]
                holder.head.text = row.title
                holder.rail.adapter = CardAdapter(row)
            }
        }
    }

    private inner class HeroHolder(
        view: View, val art: ImageView, val poster: ImageView, val title: TextView, val logo: ImageView,
        val meta: TextView, val play: TextView, val split: Boolean,
    ) : RecyclerView.ViewHolder(view)

    private inner class ShelfHolder(
        view: View, val head: TextView, val rail: RecyclerView,
    ) : RecyclerView.ViewHolder(view)

    private inner class CardAdapter(private val row: BrowseRow) : RecyclerView.Adapter<CardHolder>() {
        private val theme = app.theme
        private val style = row.card
        // The pack owns the rail shape: Midnight turns ordinary poster shelves into wide 16:9 stills,
        // Ember keeps portraits but frames them. Ranked rows keep their numeral geometry.
        private val landscape = style == "poster" && theme.rowStyle == "landscape"
        private val poster = if (landscape) false else style == "poster" || style == "rank" || style == "hero"
        private val ranked = style == "rank"
        private val frame = theme.cardStyle == "frame"
        private val glass = theme.cardStyle == "glass"
        // Apple's scene cards keep the name under the art instead of over it.
        private val below = theme.cardStyle == "below"
        private val captionH = when {
            below -> theme.px(62f)
            frame -> theme.px(56f)
            else -> 0
        }
        private val cardW = theme.px(if (landscape) 480f else if (poster) 280f else 320f)
        private val cardH = theme.px(if (landscape) 270f else if (poster) 420f else 180f)
        private val radius = theme.radius("card", 6f)

        override fun getItemCount(): Int = row.items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CardHolder {
            val card = FrameLayout(this@BrowseActivity)
            card.layoutParams = RecyclerView.LayoutParams(cardW, cardH + captionH)
            card.isFocusable = true
            card.isClickable = true
            card.setBackgroundColor(if (frame) theme.surface else Color.TRANSPARENT)
            val art = ImageView(this@BrowseActivity).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(theme.surface2)
                clipToOutline = true
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(v: View, outline: android.graphics.Outline) {
                        outline.setRoundRect(0, 0, v.width, v.height, theme.radius("card", 6f).toFloat())
                    }
                }
            }
            card.addView(art, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, cardH).apply {
                gravity = Gravity.TOP
            })
            if (!frame && !below) {
                val shade = View(this@BrowseActivity).apply {
                    background = GradientDrawable(
                        GradientDrawable.Orientation.BOTTOM_TOP,
                        intArrayOf(Color.argb(if (glass) 190 else 235, 0, 0, 0), Color.TRANSPARENT)
                    )
                }
                card.addView(shade, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, theme.px(140f)).apply {
                    gravity = Gravity.BOTTOM
                    bottomMargin = captionH
                })
            }
            if (frame) {
                // An editorial card is a printed plate: opaque caption bar, hairline border, art above.
                card.addView(View(this@BrowseActivity).apply { setBackgroundColor(theme.surface) },
                    FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, captionH).apply {
                        gravity = Gravity.BOTTOM
                    })
                card.addView(View(this@BrowseActivity).apply {
                    background = Views.ring(theme.line, radius, theme.px(1f))
                }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            } else if (glass) {
                card.addView(View(this@BrowseActivity).apply {
                    background = Views.rounded(Color.argb(150, 8, 10, 14),
                        theme.radius("card", 6f), theme.px(1f), Color.argb(90, 255, 255, 255))
                }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, theme.px(84f)).apply {
                    gravity = Gravity.BOTTOM
                    leftMargin = theme.px(8f); rightMargin = theme.px(8f); bottomMargin = theme.px(8f)
                })
            }
            val name = Views.text(app, "caption", if (below) 18f else 19f, theme.ink, "", bold = true,
                maxLines = if (below) 1 else 2)
            name.setPadding(theme.px(if (below) 4f else 12f), 0,
                theme.px(if (below) 4f else 12f), theme.px(if (below) 0f else 10f))
            card.addView(name, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.BOTTOM
                bottomMargin = when {
                    below -> theme.px(26f)
                    frame -> theme.px(8f)
                    else -> 0
                }
            })
            val sub = Views.text(app, "meta", 14f, theme.ink3, "", maxLines = 1)
            sub.setPadding(theme.px(4f), 0, theme.px(4f), theme.px(4f))
            sub.visibility = if (below) View.VISIBLE else View.GONE
            card.addView(sub, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.BOTTOM
                bottomMargin = theme.px(6f)
            })
            val logo = ImageView(this@BrowseActivity).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                visibility = View.GONE
            }
            card.addView(logo, FrameLayout.LayoutParams(cardW - theme.px(28f),
                if (landscape) theme.px(64f) else theme.px(76f)).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                bottomMargin = when {
                    below -> captionH + theme.px(10f)
                    frame -> theme.px(10f)
                    else -> theme.px(8f)
                }
            })
            val badge = Views.text(app, "meta", 13f, Color.WHITE, "", bold = true, maxLines = 1)
            badge.setPadding(theme.px(8f), theme.px(3f), theme.px(8f), theme.px(3f))
            badge.background = Views.rounded(Color.argb(185, 8, 8, 8), theme.px(5f))
            card.addView(badge, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.TOP or Gravity.START
                leftMargin = theme.px(10f); topMargin = theme.px(10f)
            })
            val num = Views.text(app, "h1", 0f, Color.WHITE, "", bold = true, gravity = Gravity.CENTER)
            num.visibility = if (ranked) View.VISIBLE else View.GONE
            card.addView(num, FrameLayout.LayoutParams(theme.px(110f), ViewGroup.LayoutParams.MATCH_PARENT).apply {
                gravity = Gravity.START
            })
            val progress = android.widget.ProgressBar(this@BrowseActivity, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 1000
                progressDrawable = GradientDrawable().apply {
                    setColor(Color.argb(210, 229, 9, 20))
                    cornerRadius = theme.px(3f).toFloat()
                }
                visibility = View.GONE
            }
            card.addView(progress, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, theme.px(6f)).apply {
                gravity = Gravity.BOTTOM
                bottomMargin = captionH
            })
            val holder = CardHolder(card, art, name, sub, logo, badge, num, progress)
            Focus.attach(card, card, app, theme.radius("card", 6f), animateScale = false, elevate = false,
                onFocus = { focused -> if (focused) holder.item?.let { onCardFocused(card, it) } })
            return holder
        }

        override fun onBindViewHolder(holder: CardHolder, position: Int) {
            val item = row.items[position]
            holder.item = item
            holder.name.text = item.title
            holder.name.visibility = View.VISIBLE
            holder.sub.visibility = if (below) View.VISIBLE else View.GONE
            if (below) {
                val sub = ArrayList<String>()
                if (item.year > 0) sub.add(item.year.toString())
                if (item.match > 0) sub.add("${item.match}% match")
                item.genres.firstOrNull()?.takeIf { it.isNotBlank() }?.let { sub.add(it) }
                holder.sub.text = sub.joinToString(" · ")
            }
            holder.logo.visibility = View.GONE
            val facts = ArrayList<String>()
            if (item.isNew) facts.add("NEW")
            if (item.match > 0) facts.add("${item.match}% match")
            holder.badge.text = facts.joinToString(" · ")
            holder.badge.visibility = if (facts.isEmpty()) View.GONE else View.VISIBLE
            BrowseImages.load(holder.art,
                if (landscape) item.backdrop ?: item.poster else item.poster ?: item.backdrop, cardW)
            if (!item.logo.isNullOrBlank()) {
                holder.logo.visibility = View.VISIBLE
                BrowseImages.load(holder.logo, item.logo, cardW) { ok ->
                    holder.logo.visibility = if (ok) View.VISIBLE else View.GONE
                    // A scene card keeps its name under the art even when the art carries a logo.
                    holder.name.visibility = if (ok && !below) View.GONE else View.VISIBLE
                }
            }
            if (ranked) {
                holder.num.text = (position + 1).toString()
                holder.num.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, cardH * 0.44f)
                holder.num.setShadowLayer(theme.px(6f).toFloat(), 0f, 0f, Color.BLACK)
                if (theme.packString("rank", "solid") == "outline") {
                    // Apple's board numerals are hollow, not filled: the art stays visible through them.
                    holder.num.setTextColor(Color.WHITE)
                    holder.num.paint.style = android.graphics.Paint.Style.STROKE
                    holder.num.paint.strokeWidth = theme.px(3f).toFloat()
                } else {
                    holder.num.setTextColor(Color.WHITE)
                    holder.num.paint.style = android.graphics.Paint.Style.FILL
                }
            }
            val p = item.progress
            if (p != null && p.watchedMs > 0L) {
                holder.progress.visibility = View.VISIBLE
                holder.progress.progress = ((p.positionMs.toFloat() / p.watchedMs.toFloat()).coerceIn(0f, 1f) * 1000).toInt()
            } else {
                holder.progress.visibility = View.GONE
            }
            holder.itemView.setOnClickListener { openDetail(item) }
        }
    }

    private inner class CardHolder(
        view: View, val art: ImageView, val name: TextView, val sub: TextView, val logo: ImageView,
        val badge: TextView, val num: TextView, val progress: android.widget.ProgressBar,
    ) : RecyclerView.ViewHolder(view) {
        var item: Item? = null
    }

    companion object {
        const val EXTRA_PROFILE = "profile"
        private const val TYPE_HERO = 1
        private const val TYPE_SHELF = 2
    }
}
