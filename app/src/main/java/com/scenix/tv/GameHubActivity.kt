package com.scenix.tv

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.scenix.tv.core.AppContext
import com.scenix.tv.core.Console
import com.scenix.tv.core.Focus
import com.scenix.tv.core.GameSessions
import com.scenix.tv.core.Motion
import com.scenix.tv.core.Views
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A game's own page, built on the console reference's second composition: the key art keeps the
 * right of the screen, while a darker reading column on the left carries the category tabs, the
 * title, the facts that are actually known about this port, and the sessions played here.
 *
 * The reference's trophy percentage has no honest analogue in a browser arcade — there is no
 * campaign funnel to measure — so that slot states the one thing we can verify: the game is ready
 * to launch. "Continue from save point" becomes the local session history instead of pretending
 * to enumerate saves that live inside the engine's own storage.
 */
class GameHubActivity : Activity() {

    private lateinit var app: AppContext
    private lateinit var identity: String
    private lateinit var title: String
    private lateinit var playUrl: String
    private lateinit var fallbackUrl: String
    private lateinit var poster: String
    private lateinit var backdrop: String
    private lateinit var description: String
    private lateinit var category: String
    private var coi = false
    private var pad = false
    private var kb = true
    private var touch = false
    private var desktopUa = false

    private lateinit var tabsRow: LinearLayout
    private val tabViews = ArrayList<View>()
    private lateinit var blockPlay: View
    private lateinit var blockControls: View
    private lateinit var blockAbout: View
    private lateinit var startBox: TextView
    private lateinit var sessionsBox: LinearLayout
    private var activeTab = 0

    private val ticker = Handler(Looper.getMainLooper())
    private val clockFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    private var clockTick: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        app = (application as ScenixApp).ctx
        val i = intent
        identity = i?.getStringExtra(EXTRA_IDENTITY).orEmpty()
        title = i?.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { "Game" }
        playUrl = i?.getStringExtra(EXTRA_URL).orEmpty()
        fallbackUrl = i?.getStringExtra(EXTRA_FALLBACK).orEmpty()
        poster = i?.getStringExtra(EXTRA_POSTER).orEmpty()
        backdrop = i?.getStringExtra(EXTRA_BACKDROP).orEmpty()
        description = i?.getStringExtra(EXTRA_DESC).orEmpty()
        category = i?.getStringExtra(EXTRA_CATEGORY).orEmpty()
        coi = i?.getBooleanExtra(EXTRA_COI, false) ?: false
        pad = i?.getBooleanExtra(EXTRA_PAD, false) ?: false
        kb = i?.getBooleanExtra(EXTRA_KB, true) ?: true
        touch = i?.getBooleanExtra(EXTRA_TOUCH, false) ?: false
        desktopUa = i?.getBooleanExtra(EXTRA_DESKTOP_UA, false) ?: false
        if (identity.isBlank()) identity = title

        val theme = app.theme
        val root = FrameLayout(this).apply { setBackgroundColor(Color.parseColor("#0B0D11")) }

        val stage = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(Console.surface)
        }
        root.addView(stage, match())
        BrowseImages.load(stage, backdrop.ifBlank { poster }, theme.px(1600f))

        // The reading column is darker than the library's wash: the console pushes the art to the
        // right here so the title, facts and sessions stand on calm surface.
        root.addView(View(this).apply {
            // Four evenly spaced stops keep the wash dense through the reading column and almost
            // clear by the right edge, where the key art stays alive.
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(
                    Color.argb(236, 6, 8, 12), Color.argb(210, 6, 8, 12),
                    Color.argb(120, 6, 8, 12), Color.argb(10, 6, 8, 12),
                ),
            )
        }, match())
        root.addView(View(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.BOTTOM_TOP,
                intArrayOf(Color.argb(200, 4, 6, 10), Color.TRANSPARENT),
            )
        }, match())

        // The reference divides the reading column from the art with a hairline; it is placed at
        // the same proportion after the window has a width.
        val edge = View(this).apply { setBackgroundColor(Color.argb(46, 255, 255, 255)) }
        root.addView(edge, FrameLayout.LayoutParams(theme.px(1f), ViewGroup.LayoutParams.MATCH_PARENT).apply {
            gravity = Gravity.TOP or Gravity.START
            leftMargin = (resources.displayMetrics.widthPixels * 0.58f).toInt()
        })

        root.addView(makeStatusStrip(), FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, theme.px(72f),
        ).apply {
            gravity = Gravity.TOP
            leftMargin = theme.gutter
            rightMargin = theme.gutter
            topMargin = theme.px(20f)
        })

        // Category tabs sit above the title, exactly as the console prints them: a pill row where
        // only the current tab wears the electric box, with (L1)/(R1) shoulders.
        tabsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        addTab("PLAY", 0)
        addTab("CONTROLS", 1)
        addTab("ABOUT", 2)
        root.addView(tabsRow, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, theme.px(48f),
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            leftMargin = theme.gutter
            topMargin = theme.px(150f)
        })

        root.addView(Views.text(app, "meta", 12f, Console.ink3, "SCENIX GAME", bold = true).apply {
            letterSpacing = 0.30f
        }, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            leftMargin = theme.gutter
            topMargin = theme.px(212f)
        })

        val headline = Views.text(app, "display", 62f, Console.ink, title, bold = true, maxLines = 2)
        root.addView(headline, FrameLayout.LayoutParams(
            theme.px(1040f), ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            leftMargin = theme.gutter
            topMargin = theme.px(240f)
        })

        // The three pages share the title block; only what sits under it changes.
        blockPlay = buildPlayBlock()
        blockControls = buildControlsBlock()
        blockAbout = buildAboutBlock()
        root.addView(blockPlay, contentParams())
        root.addView(blockControls, contentParams().apply { blockControls.visibility = View.GONE })
        root.addView(blockAbout, contentParams().apply { blockAbout.visibility = View.GONE })

        buildRightRail(root)
        root.addView(makeLegend(), FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            rightMargin = theme.gutter
            bottomMargin = theme.px(34f)
        })

        setContentView(root)
        Motion.enter(theme, root)
        setTab(0, false)
        startBox.post { startBox.requestFocus() }
    }

    private fun match() = FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
    )

    private fun contentParams() = FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        leftMargin = app.theme.gutter
        // The console's left gutter is mirrored on the right: a MATCH_PARENT block that stopped at
        // the screen edge made every row bleed on one side only.
        rightMargin = app.theme.gutter
        topMargin = app.theme.px(360f)
    }

    // ---- status line --------------------------------------------------------------------------

    private fun makeStatusStrip(): View {
        val theme = app.theme
        val strip = FrameLayout(this)
        val left = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val clock = Views.text(app, "meta", 18f, Console.ink, clockFormat.format(Date()), bold = true)
        left.addView(clock)
        clockTick = object : Runnable {
            override fun run() {
                clock.text = clockFormat.format(Date())
                ticker.postDelayed(this, 30_000L)
            }
        }
        ticker.postDelayed(clockTick!!, 30_000L)
        left.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_wifi)
            setColorFilter(Console.ink, android.graphics.PorterDuff.Mode.SRC_IN)
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "Connected"
        }, LinearLayout.LayoutParams(theme.px(28f), theme.px(28f)).apply { marginStart = theme.px(12f) })
        left.addView(Views.text(app, "meta", 13f, Console.ink3, "GAMES", bold = true).apply {
            letterSpacing = 0.22f
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { marginStart = theme.px(16f) })
        strip.addView(left, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT,
        ).apply { gravity = Gravity.START or Gravity.CENTER_VERTICAL })
        return strip
    }

    // ---- tabs ---------------------------------------------------------------------------------

    private fun addTab(label: String, index: Int) {
        val theme = app.theme
        val stop = FrameLayout(this).apply {
            id = View.generateViewId()
            isFocusable = true
            isClickable = true
            contentDescription = "$label tab"
            setPadding(theme.px(15f), theme.px(8f), theme.px(15f), theme.px(8f))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { marginEnd = theme.px(4f) }
            setOnClickListener { setTab(index, true) }
            setOnFocusChangeListener { _, focused -> if (focused) setTab(index, false) }
        }
        val text = Views.text(app, "meta", 14f, Console.ink2, label, bold = true, gravity = Gravity.CENTER).apply {
            letterSpacing = 0.08f
        }
        stop.addView(text)
        tabViews += stop
        if (index == 0) tabsRow.addView(shoulder("L1"))
        tabsRow.addView(stop)
        if (index == 2) tabsRow.addView(shoulder("R1"))
    }

    private fun shoulder(label: String): View {
        val theme = app.theme
        return Views.text(app, "badge", 10f, Console.ink3, label, bold = true, gravity = Gravity.CENTER).apply {
            background = Views.rounded(Color.TRANSPARENT, theme.px(999f), theme.px(1f), Console.line)
            alpha = 0.75f
            layoutParams = LinearLayout.LayoutParams(theme.px(30f), theme.px(30f)).apply {
                marginStart = theme.px(8f)
                marginEnd = theme.px(8f)
            }
        }
    }

    private fun setTab(index: Int, focusStart: Boolean) {
        activeTab = index
        val theme = app.theme
        tabViews.forEachIndexed { i, stop ->
            val active = i == index
            stop.background = if (active) {
                Views.rounded(Color.TRANSPARENT, theme.px(999f), theme.px(2f), Console.lineStrong)
            } else null
            ((stop as ViewGroup).getChildAt(0) as? TextView)?.setTextColor(if (active) Console.ink else Console.ink2)
        }
        blockPlay.visibility = if (index == 0) View.VISIBLE else View.GONE
        blockControls.visibility = if (index == 1) View.VISIBLE else View.GONE
        blockAbout.visibility = if (index == 2) View.VISIBLE else View.GONE
        if (focusStart) startBox.requestFocus()
    }

    // ---- the play page ------------------------------------------------------------------------

    private fun buildPlayBlock(): View {
        val theme = app.theme
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val stats = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
        }
        val sessions = GameSessions.list(this, identity)
        val played = GameSessions.totalMs(this, identity)
        stats.addView(stat("PLAYED FOR", if (played > 0) formatPlayed(played) else "0M"))
        stats.addView(stat("SESSIONS", if (sessions.isNotEmpty()) sessions.size.toString() else "0"))
        stats.addView(stat("CONTROLLER", if (pad) "GAMEPAD" else "REMOTE"))
        stats.addView(stat("ENGINE", if (coi) "GECKO" else "BROWSER"))
        box.addView(stats, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ))

        sessionsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        if (sessions.isNotEmpty()) {
            sessionsBox.addView(Views.text(app, "meta", 12f, Console.ink3, "CONTINUE FROM SAVE POINT", bold = true).apply {
                letterSpacing = 0.18f
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = theme.px(66f); bottomMargin = theme.px(14f) })
            sessions.take(3).forEachIndexed { index, session -> sessionsBox.addView(sessionRow(index, session)) }
        }
        box.addView(sessionsBox, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        return box
    }

    private fun stat(label: String, value: String): View {
        val theme = app.theme
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(Views.text(app, "meta", 11f, Console.ink3, label, bold = true).apply {
            letterSpacing = 0.12f
        })
        col.addView(Views.text(app, "li", 30f, Console.ink, value, bold = true).apply {
            setPadding(0, theme.px(6f), 0, 0)
        })
        col.layoutParams = LinearLayout.LayoutParams(theme.px(236f), ViewGroup.LayoutParams.WRAP_CONTENT)
        return col
    }

    private fun formatPlayed(ms: Long): String {
        val minutes = (ms / 60_000L).coerceAtLeast(1L)
        return if (minutes >= 60) "${minutes / 60}H ${(minutes % 60).toString().padStart(2, '0')}M"
        else "${minutes}M"
    }

    private fun sessionRow(index: Int, session: GameSessions.Session): View {
        val theme = app.theme
        val row = FrameLayout(this).apply {
            id = View.generateViewId()
            isFocusable = true
            isClickable = true
            contentDescription = "Start the game again"
            // The save-point rows are list items, not cards: they span the content column exactly,
            // so the focus plate reads as a row of one list instead of a floating slab.
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, theme.px(102f),
            ).apply { bottomMargin = theme.px(10f) }
            setOnClickListener { play() }
            setOnFocusChangeListener { v, focused ->
                v.background = if (focused) {
                    Views.rounded(Color.argb(36, 255, 255, 255), theme.px(2f), theme.px(2f), Color.WHITE)
                } else null
            }
        }
        val thumb = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(Console.surface)
        }
        row.addView(thumb, FrameLayout.LayoutParams(theme.px(166f), theme.px(94f)).apply {
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            leftMargin = theme.px(6f)
        })
        BrowseImages.load(thumb, poster.ifBlank { backdrop }, theme.px(332f))

        val copy = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(theme.px(24f), 0, theme.px(16f), 0)
        }
        copy.addView(Views.text(app, "meta", 10f, Console.ink3, title.uppercase(Locale.US), bold = true).apply {
            letterSpacing = 0.14f
        })
        copy.addView(Views.text(app, "li", 23f, Console.ink, "Session ${index + 1}", bold = true).apply {
            setPadding(0, theme.px(3f), 0, 0)
        })
        copy.addView(Views.text(
            app, "meta", 12f, Console.ink3,
            SimpleDateFormat("dd/MM/yyyy · HH:mm", Locale.getDefault()).format(Date(session.startMs)),
        ).apply { setPadding(0, theme.px(5f), 0, 0) })
        row.addView(copy, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            leftMargin = theme.px(190f)
            // Keep the copy column clear of the duration printed in the row's right shoulder.
            rightMargin = theme.px(130f)
        })

        row.addView(Views.text(
            app, "meta", 13f, Console.ink2, formatPlayed(session.durationMs), bold = true,
        ), FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL; rightMargin = theme.px(18f) })
        return row
    }

    // ---- the other two pages ------------------------------------------------------------------

    private fun buildControlsBlock(): View {
        val theme = app.theme
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(Views.text(app, "meta", 12f, Console.ink3, "WHAT THIS PORT ANSWERS", bold = true).apply {
            letterSpacing = 0.18f
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = theme.px(18f) })
        box.addView(controlRow("Controller", if (pad) "Ready — play from the couch" else "Not required"))
        box.addView(controlRow("Keyboard", if (kb) "Supported" else "Not supported"))
        box.addView(controlRow("Touch", if (touch) "Supported" else "Not supported"))
        box.addView(controlRow("Engine", if (coi) "Embedded Gecko (cross-origin isolation)" else "Android WebView"))
        return box
    }

    private fun controlRow(label: String, value: String): View {
        val theme = app.theme
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, theme.px(14f), 0, theme.px(14f))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        row.addView(Views.text(app, "meta", 15f, Console.ink2, label, bold = true), LinearLayout.LayoutParams(
            theme.px(300f), ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        row.addView(Views.text(app, "meta", 15f, Console.ink, value), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        return row
    }

    private fun buildAboutBlock(): View {
        val theme = app.theme
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(Views.text(app, "sub", 19f, Console.ink2,
            description.ifBlank { "No description was supplied for this port." }, maxLines = 4),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val host = try { java.net.URI(playUrl).host.orEmpty() } catch (e: Exception) { "" }
        box.addView(controlRow("Shelf", category.ifBlank { "Games" }))
        box.addView(controlRow("Source", host.ifBlank { "—" }))
        return box
    }

    // ---- the right rail -----------------------------------------------------------------------

    private fun buildRightRail(root: FrameLayout) {
        val theme = app.theme
        val rail = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
        }

        val progressHead = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        progressHead.addView(Views.text(app, "meta", 11f, Console.ink2, "PLAY STATUS", bold = true).apply {
            letterSpacing = 0.16f
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        progressHead.addView(Views.text(app, "meta", 12f, Console.ink, "READY", bold = true).apply {
            letterSpacing = 0.12f
        })
        rail.addView(progressHead, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        rail.addView(View(this).apply {
            background = Views.rounded(Console.ink, theme.px(2f))
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, theme.px(5f),
        ).apply { topMargin = theme.px(9f) })

        // The console's action is an outlined box, never a filled brand pill.
        startBox = Views.text(app, "li", 19f, Console.ink, "START THE GAME", bold = true,
            gravity = Gravity.CENTER_VERTICAL).apply {
            letterSpacing = 0.06f
            isFocusable = true
            isClickable = true
            contentDescription = "Start the game"
            setPadding(theme.px(28f), 0, theme.px(24f), 0)
            background = startPlate(theme, false)
            setOnClickListener { play() }
            setOnFocusChangeListener { v, focused -> v.background = startPlate(theme, focused) }
        }
        rail.addView(startBox, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, theme.px(86f),
        ).apply { topMargin = theme.px(24f) })
        // The play glyph in the box's right shoulder, drawn as its own disc.
        val glyph = Views.text(app, "meta", 13f, Console.ink, "▶", bold = true, gravity = Gravity.CENTER).apply {
            background = Views.rounded(Color.TRANSPARENT, theme.px(999f), theme.px(2f), Console.ink)
        }
        rail.addView(glyph, LinearLayout.LayoutParams(theme.px(36f), theme.px(36f)).apply {
            gravity = Gravity.END
            topMargin = -theme.px(61f)
            rightMargin = theme.px(24f)
        })

        root.addView(rail, FrameLayout.LayoutParams(
            theme.px(560f), ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            rightMargin = theme.gutter
            bottomMargin = theme.px(112f)
        })
    }

    private fun startPlate(theme: com.scenix.tv.core.Theme, focused: Boolean) =
        Views.rounded(
            if (focused) Color.argb(28, 255, 255, 255) else Color.TRANSPARENT,
            theme.px(2f),
            theme.px(2f),
            if (focused) Color.WHITE else Console.lineStrong,
        )

    private fun makeLegend(): View {
        val theme = app.theme
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        for ((glyph, label) in listOf("≡" to "OPTIONS", "⏎" to "SELECT", "←" to "BACK")) {
            val group = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(theme.px(12f), 0, 0, 0)
            }
            group.addView(Views.text(app, "badge", 12f, Console.ink2, glyph, bold = true,
                gravity = Gravity.CENTER).apply {
                background = Views.rounded(Color.TRANSPARENT, theme.px(999f), theme.px(1f), Console.line)
            }, LinearLayout.LayoutParams(theme.px(28f), theme.px(28f)))
            group.addView(Views.text(app, "meta", 12f, Console.ink3, label, bold = true).apply {
                letterSpacing = 0.14f
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { marginStart = theme.px(8f) })
            row.addView(group)
        }
        return row
    }

    // ---- actions ------------------------------------------------------------------------------

    private fun play() {
        startActivity(
            Intent(this, if (coi) CoiGameActivity::class.java else GameActivity::class.java)
                .putExtra(GameActivity.EXTRA_TITLE, title)
                .putExtra(GameActivity.EXTRA_IDENTITY, identity)
                .putExtra(GameActivity.EXTRA_URL, playUrl)
                .putExtra(GameActivity.EXTRA_FALLBACK, fallbackUrl)
                .putExtra(GameActivity.EXTRA_COI, coi)
                .putExtra(GameActivity.EXTRA_PAD, pad)
                .putExtra(GameActivity.EXTRA_DESKTOP_UA, desktopUa)
        )
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_MENU -> {
                startActivity(Intent(this, ControllersActivity::class.java))
                return true
            }
            // The tab row prints (L1)/(R1); those shoulders page between its stops.
            KeyEvent.KEYCODE_BUTTON_L1 -> {
                setTab((activeTab - 1 + tabViews.size) % tabViews.size, focusStart = false)
                return true
            }
            KeyEvent.KEYCODE_BUTTON_R1 -> {
                setTab((activeTab + 1) % tabViews.size, focusStart = false)
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                play()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        clockTick?.let { ticker.removeCallbacks(it) }
        super.onDestroy()
    }

    companion object {
        const val EXTRA_IDENTITY = "identity"
        const val EXTRA_TITLE = "title"
        const val EXTRA_POSTER = "poster"
        const val EXTRA_BACKDROP = "backdrop"
        const val EXTRA_URL = "url"
        const val EXTRA_FALLBACK = "fallback"
        const val EXTRA_COI = "coi"
        const val EXTRA_PAD = "pad"
        const val EXTRA_KB = "kb"
        const val EXTRA_TOUCH = "touch"
        const val EXTRA_DESKTOP_UA = "desktopUa"
        const val EXTRA_DESC = "desc"
        const val EXTRA_CATEGORY = "category"
        const val EXTRA_PROFILE = "profile"
    }
}
