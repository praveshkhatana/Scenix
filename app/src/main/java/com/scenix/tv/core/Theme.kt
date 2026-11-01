package com.scenix.tv.core

import android.content.res.AssetManager
import android.animation.ValueAnimator
import android.graphics.Color
import android.util.Log
import org.json.JSONObject
import org.json.JSONArray
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Theme = one complete pack from `assets/theme-*.json`: colour and type tokens plus the `pack`
 * block that chooses the layout (full/split/centred hero), the rail shape (poster/landscape), the
 * card treatment (overlay/frame/glass), the focus effect (ring/glow/lift), the motion language and
 * the detail-page shape. [homeLayout] is the structural contract for the entire Home composition;
 * it prevents a PS5 or Netflix pack from inheriting an Apple shell just because its token values
 * happen to be similar. The native screens ask [packString] rather than branching on a theme id, so
 * a developer adds a pack by writing the JSON and one assets/themes.json row.
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
    private val pack: JSONObject,
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
    /** Text/icon colour that stays readable on the brand: Apple's white pill needs black lettering. */
    val onBrand: Int get() = if (Color.luminance(brand) > 0.55f) Color.BLACK else Color.WHITE

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

    /**
     * Android TV exposes "remove animations" through the system animator scale.  A focus change
     * must still be obvious in that mode, but it must settle immediately rather than leaving a
     * half-lifted tile on screen.  Keeping this with the theme makes every native surface use the
     * same accessibility contract.
     */
    val motionEnabled get() = ValueAnimator.areAnimatorsEnabled()

    // ---- the pack: what this theme is allowed to change beyond tokens ------------------------
    /** A pack choice, e.g. packString("hero", "full"). Unknown keys keep the classic behaviour. */
    fun packString(key: String, fallback: String): String =
        pack.optString(key, fallback).takeIf { it.isNotBlank() } ?: fallback
    fun packInt(key: String, fallback: Int): Int = pack.optInt(key, fallback)

    /**
     * The complete Home composition selected by a pack.  This is intentionally typed and validated
     * rather than exposing `homeLayout` as another arbitrary string at every call site.
     */
    val homeLayout: HomeLayoutSpec get() = HomeLayoutSpec.from(pack)

    /**
     * The home billboard is a component rather than a screen-specific arrangement.  A theme pack
     * can therefore describe its structure without Browse needing to know whether it is an Apple,
     * Netflix, PS5, Roku, Clean, or Magnifier family.  Unknown values deliberately resolve to the
     * long-standing full-bleed layout so a third-party pack cannot make Home unusable.
     */
    data class HeroLayout(
        val style: String,
        val alignment: String,
        val heightPx: Int,
        val copyMode: String,
        val artworkMode: String,
        val posterMode: String,
        val dotsStyle: String,
        val titleScale: Float,
        val titleLines: Int,
        val posterWidthPx: Int,
        val posterHeightPx: Int,
    ) {
        val split: Boolean get() = style == "split" || copyMode == "side"
        val centered: Boolean get() = alignment == "center" || style == "centred" || style == "centered"
        val showArtwork: Boolean get() = artworkMode != "none"
        val showPoster: Boolean get() = posterMode == "show" || (posterMode == "auto" && split)
        val showDots: Boolean get() = dotsStyle != "none"
    }

    /**
     * Optional pack keys:
     * `heroCopy` (`overlay`/`side`), `heroArtwork` (`backdrop`/`none`),
     * `heroPoster` (`auto`/`show`/`hide`), `heroDots` (`line`/`dot`/`none`),
     * `heroTitleScale`, `heroTitleLines`, `heroPosterWidth`, and `heroPosterHeight`.
     * They make a new theme declarative while retaining the JSON packs already in the field.
     */
    val heroLayout: HeroLayout
        get() {
            val style = heroStyle.trim().lowercase()
            val alignment = heroAlign.trim().lowercase()
            val copyMode = packString("heroCopy", if (style == "split") "side" else "overlay")
                .trim().lowercase()
            val artworkMode = packString("heroArtwork", "backdrop").trim().lowercase()
            val posterMode = packString("heroPoster", if (style == "split") "show" else "hide")
                .trim().lowercase()
            val dotsStyle = packString("heroDots", "line").trim().lowercase()
            val requestedHeight = heroHeightPx
            val split = style == "split" || copyMode == "side"
            return HeroLayout(
                style = style,
                alignment = alignment,
                heightPx = if (split) requestedHeight else requestedHeight.coerceAtLeast(px(540f)),
                copyMode = copyMode,
                artworkMode = artworkMode,
                posterMode = posterMode,
                dotsStyle = dotsStyle,
                titleScale = pack.optDouble("heroTitleScale", 1.0).toFloat().coerceIn(0.72f, 1.85f),
                titleLines = packInt("heroTitleLines", 1).coerceIn(1, 3),
                posterWidthPx = px(packInt("heroPosterWidth", 300).toFloat()),
                posterHeightPx = px(packInt("heroPosterHeight", 450).toFloat()),
            )
        }

    val heroStyle get() = packString("hero", "full")
    val heroAlign get() = packString("heroAlign", "left")
    val heroHeightPx get() = px(packInt("heroHeight", 540).toFloat())
    val rowStyle get() = packString("rows", "poster")
    val cardStyle get() = packString("card", "overlay")
    val focusStyle get() = packString("focus", "ring")
    val motionStyle get() = packString("motion", "rise")
    val detailStyle get() = packString("detail", "backdrop")
    val navStyle get() = packString("nav", "classic")
    val focusGlowColor get() = parseColor(pack.optString("focusColor", "#FFFFFF"))

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
        /**
         * The pack a device falls back to when its saved theme is not shipped any more. Packs come
         * and go — a household left holding a retired id must land on a real look, not a half-loaded
         * one — so one id owns that answer and every fallback reads it.
         */
        const val DEFAULT_ID = "scenix-apple"

        /** One selectable theme from assets/themes.json. `pack` names its layout family. */
        data class Choice(val id: String, val name: String, val asset: String, val pack: String = "")

        /** What the settings picker offers. A missing manifest keeps the built-in default only. */
        fun catalog(assets: AssetManager): List<Choice> {
            val out = ArrayList<Choice>()
            try {
                val text = assets.open("themes.json").bufferedReader().use { it.readText() }
                val arr = JSONObject(text).optJSONArray("themes") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val id = o.optString("id").trim()
                    val asset = o.optString("asset").trim()
                    if (id.isBlank() || asset.isBlank()) continue
                    out.add(Choice(id, o.optString("name", id), asset, o.optString("pack", "")))
                }
            } catch (t: Throwable) {
                Log.w(TAG, "assets/themes.json missing or unreadable", t)
            }
            if (out.isEmpty()) out.add(Choice(DEFAULT_ID, "Clean", "theme-apple.json", "Scene"))
            return out
        }

        /** A saved id whose pack this build no longer ships resolves to the default pack. */
        fun assetFor(assets: AssetManager, id: String): String {
            val all = catalog(assets)
            return all.firstOrNull { it.id == id }?.asset
                ?: all.firstOrNull { it.id == DEFAULT_ID }?.asset
                ?: ASSET
        }

        fun load(assets: AssetManager, screenW: Int, screenH: Int, asset: String = ASSET, zoom: Float = 1f): Theme {
            var root = JSONObject()
            try {
                assets.open(asset).use { inp ->
                    BufferedReader(InputStreamReader(inp, "UTF-8")).use { r ->
                        val sb = StringBuilder()
                        var line: String?
                        while (r.readLine().also { line = it } != null) sb.append(line)
                        root = JSONObject(sb.toString())
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "assets/$asset missing or unreadable — using built-in defaults", t)
            }
            val canvas = root.optJSONObject("canvas")
            val cw = canvas?.optInt("width", 1920) ?: 1920
            val ch = canvas?.optInt("height", 1080) ?: 1080
            // The household zoom multiplies the whole canvas, so text, tiles, gaps and the hero all
            // grow together — a size option for far couches and weak eyes, not a font patch.
            val scale = minOf(screenW.toFloat() / cw.toFloat(), screenH.toFloat() / ch.toFloat()) *
                zoom.coerceIn(1f, 1.3f)
            Log.i(TAG, "theme ${root.optString("id", "?")} scale=$scale (${screenW}x$screenH)")
            return Theme(
                id = root.optString("id", DEFAULT_ID),
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
                pack = root.optJSONObject("pack") ?: JSONObject(),
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
