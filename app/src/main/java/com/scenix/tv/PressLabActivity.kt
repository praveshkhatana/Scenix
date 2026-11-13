package com.scenix.tv

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.scenix.tv.core.AppContext
import com.scenix.tv.core.Focus
import com.scenix.tv.core.Theme

/**
 * The press rules, tested on the television that has to answer them.
 *
 * A real hold is one physical press: a DOWN, the autorepeats a remote sends while the button stays down, and a
 * release that carries that same press's `downTime`. The sheet the hold opens takes focus before the release
 * lands, so the rest of the press arrives at a view that never saw the button go down — and if that view answers
 * it, the viewer watched a menu open and an option choose itself. No `adb shell input` command can send this
 * (it releases in the millisecond it presses), which is how the bug outlived every scripted test.
 *
 * So the presses are synthesized here, with the timings a remote really produces, and run through the real
 * [Focus.attach] machinery on the box under test:
 *
 *     adb shell am start -n com.scenix.tv/.PressLabActivity
 *     adb logcat -s ScenixPressLab
 *
 * Debug builds only. It needs no hub, no profile and no remote.
 */
class PressLabActivity : Activity() {

    private lateinit var app: AppContext
    private val main = Handler(Looper.getMainLooper())
    private lateinit var stage: LinearLayout

    private var cardOpens = 0
    private var cardHolds = 0
    private var optionTaps = 0
    private var option: View? = null

    private var passed = 0
    private var failed = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = (application as ScenixApp).ctx
        // `--es theme scenix-glass` puts the television on that pack and leaves: the theme a device runs
        // is a saved preference like any other, so this is the same road the Settings picker takes. It is
        // here rather than in a shell script because the pack has to be one the build really ships.
        intent?.getStringExtra(EXTRA_THEME)?.takeIf { it.isNotBlank() }?.let { id ->
            val shipped = Theme.catalog(this, assets).any { it.id == id }
            if (shipped) {
                app.prefs.setTheme(id)
                (application as ScenixApp).reloadTheme()
            }
            Log.i(TAG, "theme -> ${if (shipped) id else "refused, no pack named $id in this build"}")
            finish()
            return
        }
        stage = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(app.theme.px(40f), app.theme.px(40f), app.theme.px(40f), app.theme.px(40f))
        }
        setContentView(stage)
        runScenario(0)
    }

    // ---- the two views a press can land on -----------------------------------------------------------

    /** A card like a shelf tile: OK opens the title, holding OK opens the options. */
    private fun buildCard() {
        stage.removeAllViews()
        option = null
        val card = TextView(this).apply {
            text = "a card: OK opens it, holding OK opens its options"
            setTextColor(app.theme.ink)
            isFocusable = true
            isClickable = true
            isLongClickable = true
            setOnClickListener { cardOpens++ }
            setOnLongClickListener { cardHolds++; openOption(); true }
        }
        Focus.attach(card, null, app, 0, scale = false, elevate = false)
        stage.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, app.theme.px(120f)))
        card.requestFocus()
    }

    /** The option the sheet hands focus to — a view that never saw the press that opened it. */
    private fun openOption() {
        val row = TextView(this).apply {
            text = "an option the sheet opened"
            setTextColor(app.theme.ink)
            isFocusable = true
            isClickable = true
            setOnClickListener { optionTaps++; closeOption() }
        }
        Focus.attach(row, null, app, 0, scale = false, elevate = false)
        stage.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, app.theme.px(90f)))
        option = row
        // Like CardActionSheet: the focus is handed over once the panel is up, not while it is being built.
        row.post { row.requestFocus() }
    }

    private fun closeOption() {
        option?.let { stage.removeView(it) }
        option = null
    }

    // ---- presses, synthesized the way a remote sends them ------------------------------------------

    /**
     * One physical press. `holdMs` is how long the button stays down; `repeats` is how many autorepeats the
     * remote sends in that time (a TV remote sends several; some boxes send none and leave the hold to be
     * timed by the app). Every event carries the press's own `downTime`, and each one is dispatched the way
     * the window dispatches it — to whatever view holds focus at that moment.
     */
    private fun press(holdMs: Long, repeats: Int, keyCode: Int = KeyEvent.KEYCODE_DPAD_CENTER, then: () -> Unit) {
        val t0 = SystemClock.uptimeMillis()
        send(t0, t0, KeyEvent.ACTION_DOWN, keyCode, 0)
        var i = 1
        while (i <= repeats && i * 250L < holdMs) {
            send(t0, t0 + i * 250L, KeyEvent.ACTION_DOWN, keyCode, i)
            i++
        }
        send(t0, t0 + holdMs, KeyEvent.ACTION_UP, keyCode, 0)
        main.postDelayed(then, holdMs + 500)
    }

    private fun send(t0: Long, at: Long, action: Int, keyCode: Int, repeat: Int) {
        main.postDelayed({
            dispatchKeyEvent(KeyEvent(t0, at, action, keyCode, repeat))
        }, at - t0)
    }

    // ---- the scenarios -----------------------------------------------------------------------------

    /** `opens`, `holds` and `taps` are the running totals after this scenario, not its own. */
    private fun check(label: String, opens: Int, holds: Int, taps: Int, sheetUp: Boolean? = null) {
        val ok = cardOpens == opens && cardHolds == holds && optionTaps == taps &&
            (sheetUp == null || (option != null) == sheetUp)
        if (ok) passed++ else failed++
        Log.d(TAG, "%s %s | got opens=%d hold=%d option=%d sheet=%s".format(
            if (ok) "PASS" else "FAIL", label, cardOpens, cardHolds, optionTaps,
            if (option != null) "up" else "down",
        ))
        if (!ok) Log.d(TAG, "     wanted opens=%d hold=%d option=%d%s".format(
            opens, holds, taps,
            if (sheetUp == null) "" else " sheet=${if (sheetUp) "up" else "down"}",
        ))
    }

    private fun runScenario(i: Int) {
        when (i) {
            0 -> { buildCard(); press(120, 0) {
                    check("a quick OK opens the title, once", 1, 0, 0); runScenario(i + 1) } }
            1 -> { buildCard(); press(900, 3) {
                    // The bug this file exists for: the repeats and the release of the press that opened the
                    // sheet must not be read as a fresh press on the option that just took focus.
                    check("a held OK opens the options and chooses nothing", 1, 1, 0, sheetUp = true)
                    runScenario(i + 1) } }
            2 -> { buildCard(); press(900, 0) {
                    // A box that sends no autorepeats: the app's own clock still has to see the hold, and
                    // still owes the release no second decision.
                    check("a held OK with no autorepeats is still a hold", 1, 2, 0, sheetUp = true)
                    runScenario(i + 1) } }
            3 -> { press(120, 0) {
                    // The sheet is up from the scenario before: a press of its own answers its option.
                    check("a fresh OK on the option the sheet opened chooses it", 1, 2, 1, sheetUp = false)
                    runScenario(i + 1) } }
            4 -> { buildCard(); press(900, 3, KeyEvent.KEYCODE_MENU) {
                    check("the key opens the same options, and chooses nothing", 1, 3, 1, sheetUp = true)
                    runScenario(i + 1) } }
            5 -> { press(900, 3) {
                    // An option has no hold action of its own, but a press still owes it an answer: exactly one.
                    check("a held OK on an option chooses it once", 1, 3, 2, sheetUp = false)
                    runScenario(i + 1) } }
            6 -> { buildCard(); press(120, 0) {
                    press(120, 0) {
                        check("two quick presses in a row both act", 3, 3, 2)
                        runScenario(i + 1)
                    } } }
            else -> {
                Log.d(TAG, "%d scenarios, %d failed".format(passed + failed, failed))
                finish()
            }
        }
    }

    private companion object {
        const val TAG = "ScenixPressLab"
        const val EXTRA_THEME = "theme"
    }
}
