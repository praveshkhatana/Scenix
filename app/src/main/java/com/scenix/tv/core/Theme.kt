package com.scenix.tv.core

import android.content.res.AssetManager
import android.graphics.Color
import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Theme = the tokens in `assets/theme.json`, nothing else. Colours, radii, spacing, the type
 * scale, the focus ring and animation timing all come from that file, so "focus animation /
 * spacing / light theme" is a theme.json edit (plugin-architecture.md §4).
 *
 * The mockup is authored at 1920x1080 in pixels. Device pixels are those numbers times
 * `scale = min(screenW/1920, screenH/1080)`, which keeps the layout identical to the mockup on
 * the 1080p BRAVIA and correct on a 720p set, without depending on the TV's odd density bucket.
 */
class Theme private constructor(
    val id: String,
    val canvasWidth: Int,
    val canvasHeight: Int,
    val scale: Float,
    private val root: JSONObject,
    private val colors: JSONObject,
    private val radii: JSONObject,
    private val spacing: JSONObject,
    private val type: JSONObject,
    private val focus: JSONObject,
    private val player: JSONObject,
) {
    // ---- colours ---------------------------------------------------------------------------
    fun color(name: String, fallback: String = "#FFFFFF"): Int = parseColor(colors.optString(name, fallback))

    val bg get() = color("bg", "#141414")
    val surface get() = color("surface", "#181818")
    val surface2 get() = color("surface2", "#232323")
    val line get() = color("line", "#262626")
    val ink get() = color("ink", "#FFFFFF")
    val ink2 get() = color("ink2", "#B3B3B3")
    val ink3 get() = color("ink3", "#7D7D7D")
    val brand get() = color("brand", "#E50914")

    // ---- spacing / metrics, already in device pixels ---------------------------------------
    fun space(name: String, fallback: Float): Int = px(spacing.optDouble(name, fallback.toDouble()).toFloat())
    fun radius(name: String, fallback: Float): Int = px(radii.optDouble(name, fallback.toDouble()).toFloat())

    val gutter get() = space("gutter", 64f)

    // ---- type scale (device pixels; callers use COMPLEX_UNIT_PX) ----------------------------
    fun type(name: String, fallback: Float): Float =
        (type.optDouble(name, fallback.toDouble()).toFloat()) * scale

    // ---- focus behaviour --------------------------------------------------------------------
    val focusRingPx get() = px(focus.optDouble("ringWidth", 4.0).toFloat())
    val focusRingColor get() = color("focusRing", "#FFFFFF")
    val focusScale get() = focus.optDouble("scale", 1.05).toFloat()
    val focusDurationMs get() = focus.optInt("durationMs", 200)
    val focusElevationPx get() = px(focus.optDouble("shadowElevation", 12.0).toFloat())

    // ---- player ceilings --------------------------------------------------------------------
    val playerMaxWidth get() = player.optInt("maxVideoWidth", 1280)
    val playerMaxHeight get() = player.optInt("maxVideoHeight", 720)
    val playerMaxBitrate get() = player.optInt("maxBitrateBps", 6_000_000)
    val playerBackBufferMs get() = player.optInt("backBufferMs", 30_000)
    val playerForwardBufferMs get() = player.optInt("forwardBufferMs", 120_000)
    val playerMinBufferMs get() = player.optInt("minBufferMs", 15_000)
    val playerPlaybackBufferMs get() = player.optInt("playbackBufferMs", 5_000)
    val playerMinWidth get() = player.optInt("minVideoWidth", 320)
    val playerMinHeight get() = player.optInt("minVideoHeight", 180)
    val seekBackMs get() = player.optInt("seekBackMs", 10_000)
    val seekForwardMs get() = player.optInt("seekForwardMs", 10_000)

    /** Convert a mockup pixel to a device pixel. */
    fun px(v: Float): Int = Math.round(v * scale)

    /** Generic token read so a NEW plugin can own its own tokens without editing this file. */
    fun token(group: String, key: String, fallback: Double): Double =
        root.optJSONObject(group)?.optDouble(key, fallback) ?: fallback

    companion object {
        private const val TAG = "ScenixTheme"
        const val ASSET = "theme.json"

        fun load(assets: AssetManager, screenW: Int, screenH: Int): Theme {
            var root = JSONObject()
            try {
                assets.open(ASSET).use { inp ->
                    BufferedReader(InputStreamReader(inp, "UTF-8")).use { r ->
                        val sb = StringBuilder()
                        var line: String?
                        while (r.readLine().also { line = it } != null) sb.append(line)
                        root = JSONObject(sb.toString())
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "assets/$ASSET missing or unreadable — using built-in defaults", t)
            }
            val canvas = root.optJSONObject("canvas")
            val cw = canvas?.optInt("width", 1920) ?: 1920
            val ch = canvas?.optInt("height", 1080) ?: 1080
            val scale = minOf(screenW.toFloat() / cw.toFloat(), screenH.toFloat() / ch.toFloat())
            Log.i(TAG, "theme ${root.optString("id", "?")} scale=$scale (${screenW}x$screenH)")
            return Theme(
                id = root.optString("id", "scenix-dark"),
                canvasWidth = cw,
                canvasHeight = ch,
                scale = if (scale <= 0f) 1f else scale,
                root = root,
                colors = root.optJSONObject("colors") ?: JSONObject(),
                radii = root.optJSONObject("radii") ?: JSONObject(),
                spacing = root.optJSONObject("spacing") ?: JSONObject(),
                type = root.optJSONObject("type") ?: JSONObject(),
                focus = root.optJSONObject("focus") ?: JSONObject(),
                player = root.optJSONObject("player") ?: JSONObject(),
            )
        }

        /** `#RGB`, `#RRGGBB`, `#AARRGGBB` and `rgba(r,g,b,a)` — the forms used by the mockup CSS. */
        fun parseColor(s: String): Int {
            val v = s.trim()
            return try {
                if (v.startsWith("rgba") || v.startsWith("rgb")) {
                    val inner = v.substringAfter('(').substringBefore(')')
                    val parts = inner.split(',').map { it.trim() }
                    val r = parts[0].toFloat().coerceIn(0f, 255f).toInt()
                    val g = parts[1].toFloat().coerceIn(0f, 255f).toInt()
                    val b = parts[2].toFloat().coerceIn(0f, 255f).toInt()
                    val a = if (parts.size > 3) (parts[3].toFloat() * 255f).toInt() else 255
                    Color.argb(a, r, g, b)
                } else {
                    Color.parseColor(v)
                }
            } catch (t: Throwable) {
                Color.WHITE
            }
        }
    }
}
