package com.scenix.tv

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.hardware.input.InputManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.scenix.tv.core.AppContext
import com.scenix.tv.core.Focus
import com.scenix.tv.core.Views

/**
 * The household's controller screen: which pads Android has connected right now, a live button test
 * that proves each key reaches the games, and one jump to Bluetooth pairing. Listing input devices
 * needs no permissions and never goes wrong; pairing itself stays Android's job — this screen is the
 * door to it plus everything around it, not a half-baked copy of it.
 */
class ControllersActivity : Activity() {

    private lateinit var app: AppContext
    private lateinit var rows: LinearLayout
    private lateinit var status: TextView
    private val main = Handler(Looper.getMainLooper())
    private val im by lazy { getSystemService(Context.INPUT_SERVICE) as InputManager }
    private var testLayer: FrameLayout? = null
    private var testValue: TextView? = null

    private val deviceListener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(id: Int) { main.post { if (testLayer == null) paint() } }
        override fun onInputDeviceRemoved(id: Int) { main.post { if (testLayer == null) paint() } }
        override fun onInputDeviceChanged(id: Int) {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = (application as ScenixApp).ctx
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(app.theme.bg)
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(app.theme.gutter, 0, app.theme.gutter, 0)
            setBackgroundColor(app.theme.surface)
        }
        val back = Views.text(app, "li", 26f, app.theme.ink, "‹", bold = true, gravity = Gravity.CENTER)
        back.setPadding(app.theme.px(16f), app.theme.px(6f), app.theme.px(16f), app.theme.px(6f))
        back.isFocusable = true
        back.isClickable = true
        back.setOnClickListener { finish() }
        Focus.attach(back, back, app, app.theme.px(10f), scale = false, elevate = false)
        bar.addView(back, LinearLayout.LayoutParams(app.theme.px(56f), app.theme.px(50f)))
        bar.addView(Views.text(app, "li", 26f, app.theme.ink, "Controllers", bold = true).apply {
            setPadding(app.theme.px(20f), 0, 0, 0)
        })
        bar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        status = Views.text(app, "meta", 19f, app.theme.ink2, "", maxLines = 1)
        bar.addView(status)
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, app.theme.px(88f)))

        rows = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(app.theme.gutter, app.theme.px(18f), app.theme.gutter, app.theme.px(40f))
        }
        root.addView(rows, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        paint()

        // A pad paired while this screen is open appears here by itself — the person sees pairing work.
        try {
            im.registerInputDeviceListener(deviceListener, main)
        } catch (t: Throwable) { /* the list is already right for what was connected at open */ }
    }

    // The same mask the game screens use, so a pad listed here is exactly a pad a game can use.
    private fun controllers(): List<android.view.InputDevice> {
        val gamepad = android.view.InputDevice.SOURCE_GAMEPAD
        return try {
            android.view.InputDevice.getDeviceIds().toList().mapNotNull { id ->
                try { im.getInputDevice(id) } catch (t: Throwable) { null }
            }.filter { d -> (d.sources and gamepad) == gamepad && !d.name.contains("virtual", true) }
        } catch (t: Throwable) { emptyList() }
    }

    private fun paint() {
        rows.removeAllViews()
        val pads = controllers()
        rows.addView(
            Views.text(
                app, "meta", 18f, app.theme.ink2,
                "A paired controller works in every game marked “Plays with a controller” the moment Android connects it — there is nothing to set up here. This screen shows what is connected and proves its buttons arrive.",
                maxLines = 3,
            ),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = app.theme.px(14f)
            },
        )
        if (pads.isEmpty()) {
            addRow("No controller connected", "Add one below — or just use the remote; most games play with it", "") {}
        }
        for (d in pads) {
            // What the SDK actually exposes: probe the gamepad button codes and count stick ranges.
            val buttons = d.hasKeys(*KEY_NAMES.keys.toIntArray()).count { it }
            val axes = d.getMotionRanges().count { r ->
                (r.source and (android.view.InputDevice.SOURCE_JOYSTICK or android.view.InputDevice.SOURCE_GAMEPAD)) != 0
            }
            addRow(
                d.name,
                "$buttons buttons · $axes sticks/triggers · press to test its buttons",
                "Connected",
            ) { startTest() }
        }
        if (pads.isNotEmpty()) {
            addRow("Test buttons", "Every key you press shows here as the game would receive it", "") { startTest() }
        }
        addRow("Add a controller", "Opens Android's Bluetooth pairing — put the pad in pairing mode first", "") {
            pairController()
        }
    }

    /** Pairing belongs to Android's Bluetooth stack; this screen is the door, not the room. */
    private fun pairController() {
        try {
            startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
        } catch (t: Throwable) {
            try { startActivity(Intent(Settings.ACTION_SETTINGS)) } catch (u: Throwable) {
                status.text = "Open Bluetooth settings in Android to pair a controller"
            }
        }
    }

    // ── Button test ───────────────────────────────────────────────────────────────────────────────
    // Not a gimmick: remote/IR pads reach games as Android key events, and a pad that pairs but sends
    // nothing (or maps strangely) is the complaint people actually have. This shows the raw event.

    private fun startTest() {
        stopTest()
        val layer = FrameLayout(this).apply { setBackgroundColor(Color.argb(210, 0, 0, 0)) }
        testValue = Views.text(app, "h1", 56f, app.theme.ink, "Press a button…", gravity = Gravity.CENTER, maxLines = 2)
        val note = Views.text(app, "meta", 19f, app.theme.ink3, "BACK stops the test", gravity = Gravity.CENTER)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        col.addView(testValue, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        col.addView(note, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, app.theme.px(40f)).apply {
            topMargin = app.theme.px(16f)
        })
        layer.addView(col, FrameLayout.LayoutParams(app.theme.px(900f), ViewGroup.LayoutParams.MATCH_PARENT).apply {
            gravity = Gravity.CENTER
        })
        addContentView(layer, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        testLayer = layer
    }

    private fun stopTest() {
        testLayer?.let { (it.parent as? ViewGroup)?.removeView(it) }
        testLayer = null
        testValue = null
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val tv = testValue ?: return super.dispatchKeyEvent(event)
        if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
            stopTest()
            return true
        }
        if (event.action == KeyEvent.ACTION_DOWN) {
            val who = event.device?.name?.take(24) ?: "this device"
            tv.text = KEY_NAMES[event.keyCode] ?: "Key ${KeyEvent.keyCodeToString(event.keyCode).removePrefix("KEYCODE_")}"
            status.text = "from $who"
        }
        return true
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (testLayer == null) return super.onGenericMotionEvent(event)
        val src = event.source
        if ((src and (android.view.InputDevice.SOURCE_JOYSTICK or android.view.InputDevice.SOURCE_GAMEPAD)) == 0) {
            return super.onGenericMotionEvent(event)
        }
        val x = event.getAxisValue(android.view.MotionEvent.AXIS_X)
        val y = event.getAxisValue(android.view.MotionEvent.AXIS_Y)
        if (kotlin.math.abs(x) > 0.4f || kotlin.math.abs(y) > 0.4f) {
            testValue?.text = "Left stick — moves the game sees it"
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    private fun addRow(label: String, note: String, value: String, action: () -> Unit) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(app.theme.px(20f), 0, app.theme.px(20f), 0)
            background = Views.rounded(app.theme.surface, app.theme.px(10f))
            isFocusable = true
            isClickable = true
            setOnClickListener { action() }
        }
        val words = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        words.addView(Views.text(app, "li", 23f, app.theme.ink, label, bold = true, maxLines = 1))
        if (note.isNotBlank()) {
            words.addView(Views.text(app, "meta", 16f, app.theme.ink3, note, maxLines = 1),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        row.addView(words, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (value.isNotBlank()) {
            row.addView(Views.text(app, "meta", 19f, app.theme.ink2, value, maxLines = 1, gravity = Gravity.END))
        }
        Focus.attach(row, row, app, app.theme.px(10f), scale = false, elevate = false)
        rows.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, app.theme.px(78f)).apply {
            bottomMargin = app.theme.px(8f)
        })
    }

    override fun onBackPressed() {
        if (testLayer != null) { stopTest(); return }
        super.onBackPressed()
    }

    override fun onDestroy() {
        try { im.unregisterInputDeviceListener(deviceListener) } catch (t: Throwable) {}
        super.onDestroy()
    }

    companion object {
        // Friendly names for the codes a gamepad arrives as — the vocabulary games actually speak.
        private val KEY_NAMES = mapOf(
            KeyEvent.KEYCODE_BUTTON_A to "A — the round bottom button",
            KeyEvent.KEYCODE_BUTTON_B to "B — the round right button",
            KeyEvent.KEYCODE_BUTTON_X to "X — the round left button",
            KeyEvent.KEYCODE_BUTTON_Y to "Y — the round top button",
            KeyEvent.KEYCODE_BUTTON_L1 to "Left bumper (LB / L1)",
            KeyEvent.KEYCODE_BUTTON_R1 to "Right bumper (RB / R1)",
            KeyEvent.KEYCODE_BUTTON_L2 to "Left trigger (LT / L2)",
            KeyEvent.KEYCODE_BUTTON_R2 to "Right trigger (RT / R2)",
            KeyEvent.KEYCODE_BUTTON_SELECT to "Select / Back",
            KeyEvent.KEYCODE_BUTTON_START to "Start / Options",
            KeyEvent.KEYCODE_BUTTON_MODE to "Home / Guide",
            KeyEvent.KEYCODE_BUTTON_THUMBL to "Left stick click",
            KeyEvent.KEYCODE_BUTTON_THUMBR to "Right stick click",
            KeyEvent.KEYCODE_DPAD_UP to "D-pad up",
            KeyEvent.KEYCODE_DPAD_DOWN to "D-pad down",
            KeyEvent.KEYCODE_DPAD_LEFT to "D-pad left",
            KeyEvent.KEYCODE_DPAD_RIGHT to "D-pad right",
            KeyEvent.KEYCODE_DPAD_CENTER to "D-pad center (OK)",
        )
    }
}
