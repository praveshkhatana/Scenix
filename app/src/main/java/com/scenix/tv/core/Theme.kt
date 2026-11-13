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
 * Packs come in two tiers. The curated packs are complete layouts living in assets. An
 * INSTALLED pack is a token reskin in the app's files dir (`files/packs/<id>.json` with `basedOn`
 * naming a curated pack): it paints only that layout's colours, radii, spacing, type and focus
 * values. Playback ceilings and every structural choice stay in the curated base, so a reskin can
 * never make Home unusable, change buffering behavior, or fake a layout family it cannot draw.
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
    /** The single letter a pack prints on its brand disc ("S" unless the pack names another). */
    val brandMark: String get() = root.optString("brandMark", "S").ifBlank { "S" }

    // ---- colours ---------------------------------------------------------------------------
    fun color(name: String, fallback: String = "#FFFFFF"): Int = parseColor(colors.optString(name, fallback))

    /** A colour that falls back to an already-parsed one, e.g. wall-printed headings default to ink. */
    fun colorOr(name: String, fallbackColor: Int): Int =
        if (colors.has(name)) color(name) else fallbackColor

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
    /**
     * The zoom a full-width list row takes when focused. Packs that never asked for it keep 1.0 —
     * rows stay static; the Kids stage raises it (owner, round 5: "focus show increase list-item
     * and a zoom-in") so a focused setting reads as a lifted tile, not just a ring.
     */
    val focusRowScale get() = focus.optDouble("rowScale", 1.0).toFloat()
    val focusDurationMs get() = focus.optInt("durationMs", 200)
    val focusElevationPx get() = px(focus.optDouble("shadowElevation", 12.0).toFloat())

    /**
     * The halo bands every shelf, grid and tile shares: the content-tile inset and the compact-
     * chrome inset. Screens must never hand-pick a number again — this is THE vocabulary.
     */
    val focusGlowInset get() = px(focus.optDouble("glowInset", 24.0).toFloat())
    val focusGlowInsetSm get() = px(focus.optDouble("glowInsetSm", 16.0).toFloat())

    /**
     * The three halo bands' intensities, outer to inner. The dark packs get away with a barely-
     * there wash (8/12/18); a light pack sits on cream that swallows those alphas, so it raises
     * them until the glow actually reads as a state change — measured from the reference, where
     * the focused tile wears an opaque warm band around it.
     */
    val focusGlowAlphas: IntArray get() {
        val a = focus.optJSONArray("glowAlphas")
        if (a != null && a.length() >= 3) {
            return intArrayOf(a.optInt(0, 8), a.optInt(1, 12), a.optInt(2, 18))
        }
        // Older packs (Glass) dial the three bands with one multiplier instead of an array.
        // Dropping that key made the hero halo almost invisible on the light wall — the exact
        // "good focus effect" the reference shows and our build lost (owner, 2026-11-03).
        val mult = focus.optDouble("glowAlpha", 1.0).toFloat().coerceAtLeast(0f)
        fun band(base: Int) = (base * mult).toInt().coerceIn(1, 255)
        return intArrayOf(band(8), band(12), band(18))
    }

    /**
     * The thin ring that closes the halo just outside the art. A glow colour alone vanishes on a
     * light sheet, so the reference closes its halo with a warm dark keyline — liquid-glass edge,
     * never black ink. Packs that omit it keep the glow-coloured keyline.
     */
    val focusKeylineColor get(): Int =
        if (focus.has("keylineColor")) parseColor(focus.optString("keylineColor")) else focusGlowColor
    val focusKeylineExplicit get() = focus.has("keylineColor")

    /** The keyline's weight. Packs that measured a heavier reference edge raise it; the rest keep 2. */
    val focusKeylinePx get() = px(focus.optDouble("keylineWidth", 2.0).toFloat())

    /** The shelf tile spec from `cards.poster` (width, height in device px); aspect is height/width. */
    fun posterTilePx(): Pair<Int, Int> {
        val c = root.optJSONObject("cards")?.optJSONObject("poster")
        val w = (c?.optDouble("width", 280.0) ?: 280.0).toFloat()
        val asp = (c?.optDouble("aspect", 1.5) ?: 1.5).toFloat()
        return px(w) to px(w * asp)
    }

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

        /**
         * One selectable theme. Curated packs come from assets/themes.json; an INSTALLED pack is a
         * token reskin sitting in the app's files dir (`packs/<id>.json` with `basedOn` naming a
         * curated pack). `pack` names the layout family — for an installed pack that is its base's
         * family, because a reskin paints a curated layout, it never chooses one.
         */
        data class Choice(
            val id: String,
            val name: String,
            val asset: String,
            val pack: String = "",
            val installed: Boolean = false,
            /** Targets that genuinely ship this asset and its declared renderer family. */
            val targets: Set<String> = setOf("android-tv"),
        ) {
            /** A shared choice may be mirrored to a browser without a silent visual fallback. */
            val sharedWithWeb: Boolean get() = "web" in targets
        }

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
                    val targets = targetsOf(o.optJSONArray("targets"))
                    // `themes.json` is the TV projection. Retaining the target check makes an
                    // accidentally copied web-only entry harmless instead of drawable by guesswork.
                    if (id.isBlank() || asset.isBlank() || "android-tv" !in targets) continue
                    out.add(Choice(id, o.optString("name", id), asset, o.optString("pack", ""),
                        targets = targets))
                }
            } catch (t: Throwable) {
                Log.w(TAG, "assets/themes.json missing or unreadable", t)
            }
            if (out.isEmpty()) out.add(Choice(DEFAULT_ID, "Apple", "theme-apple.json", "Scene"))
            return out
        }

        /** A saved id whose pack this build no longer ships resolves to the default pack. */
        fun assetFor(assets: AssetManager, id: String): String {
            val all = catalog(assets)
            // The Clean pack was retired in favour of Glass. A device still holding its id lands on
            // the pack that replaced it instead of silently reverting to Apple.
            if (id == "scenix-clean") all.firstOrNull { it.id == "scenix-glass" }?.let { return it.asset }
            return all.firstOrNull { it.id == id }?.asset
                ?: all.firstOrNull { it.id == DEFAULT_ID }?.asset
                ?: ASSET
        }

        /** Curated packs plus every installed reskin in `files/packs/`. */
        fun catalog(context: android.content.Context, assets: AssetManager): List<Choice> {
            val out = ArrayList(catalog(assets))
            val taken = out.map { it.id }.toHashSet()
            try {
                val dir = java.io.File(context.filesDir, "packs")
                val files: Array<java.io.File> =
                    dir.listFiles(java.io.FileFilter { f -> f.isFile && f.name.endsWith(".json") })
                        ?.sortedBy { it.name }?.toTypedArray() ?: emptyArray()
                for (f in files) {
                    try {
                        val o = JSONObject(f.readText())
                        val id = o.optString("id").trim()
                        val baseId = o.optString("basedOn").trim()
                        if (id.isBlank() || baseId.isBlank() || id in taken || !isSafeInstalledSkin(o)) continue
                        val base = out.firstOrNull { it.id == baseId } ?: continue
                        if (base.installed) continue // reskins stack on curated packs only
                        out.add(Choice(id, o.optString("name", id), "file:" + f.absolutePath,
                            base.pack, true, setOf("android-tv")))
                    } catch (t: Throwable) {
                        Log.w(TAG, "installed pack ${f.name} unreadable", t)
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "installed packs dir unreadable", t)
            }
            return out
        }

        fun assetFor(context: android.content.Context, assets: AssetManager, id: String): String {
            val all = catalog(context, assets)
            if (id == "scenix-clean") all.firstOrNull { it.id == "scenix-glass" }?.let { return it.asset }
            return all.firstOrNull { it.id == id }?.asset ?: ASSET
        }

        fun load(
            context: android.content.Context,
            assets: AssetManager,
            screenW: Int,
            screenH: Int,
            asset: String = ASSET,
            zoom: Float = 1f,
        ): Theme {
            var root = readRoot(assets, asset)
            // An installed reskin is not a pack by itself: it paints visual token groups over the
            // curated base it names in `basedOn`; playback and layout behavior remain immutable.
            val basedOn = root.optString("basedOn").trim()
            if (basedOn.isNotBlank()) {
                val baseAsset = catalog(assets).firstOrNull { it.id == basedOn }?.asset ?: ASSET
                root = mergeSkin(readRoot(assets, baseAsset), root)
            }
            val canvas = root.optJSONObject("canvas")
            val cw = canvas?.optInt("width", 1920) ?: 1920
            val ch = canvas?.optInt("height", 1080) ?: 1080
            // The household zoom multiplies the whole canvas, so text, tiles, gaps and the hero all
            // grow together — a size option for far couches and weak eyes, not a font patch.
            val scale = minOf(screenW.toFloat() / cw.toFloat(), screenH.toFloat() / ch.toFloat()) *
                zoom.coerceIn(0.85f, 1.5f)
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

        /** Read a pack root from assets, or from the files dir when the asset is `file:<path>`. */
        private fun readRoot(assets: AssetManager, asset: String): JSONObject {
            try {
                val text = if (asset.startsWith("file:")) {
                    java.io.File(asset.removePrefix("file:")).readText()
                } else {
                    assets.open(asset).bufferedReader().use { it.readText() }
                }
                return JSONObject(text)
            } catch (t: Throwable) {
                Log.w(TAG, "$asset missing or unreadable — using built-in defaults", t)
                return JSONObject()
            }
        }

        /**
         * Paint an installed reskin onto its curated base. Every accepted group is visual-only: no
         * local theme file can change decoder buffers, animation work, or a pack's layout grammar.
         */
        private fun mergeSkin(base: JSONObject, skin: JSONObject): JSONObject {
            val out = JSONObject(base.toString())
            for (group in SKIN_GROUPS) {
                val over = skin.optJSONObject(group) ?: continue
                val into = out.optJSONObject(group) ?: JSONObject().also { out.put(group, it) }
                val keys = over.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    into.put(k, over.get(k))
                }
            }
            out.put("id", skin.optString("id", out.optString("id", "scenix-dark")))
            return out
        }

        /** Defense at read time for skins installed by an older build or placed in app-private data. */
        private fun isSafeInstalledSkin(skin: JSONObject): Boolean {
            val allowedRoots = setOf("id", "name", "basedOn") + SKIN_GROUPS
            val roots = skin.keys()
            while (roots.hasNext()) if (roots.next() !in allowedRoots) return false
            var tokens = 0
            for (group in SKIN_GROUPS) {
                if (!skin.has(group)) continue
                val values = skin.optJSONObject(group) ?: return false
                if (values.length() > 96) return false
                val keys = values.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    if (!Regex("^[A-Za-z][A-Za-z0-9]{0,47}$").matches(key)) return false
                    if (!safeSkinToken(group, key, values.opt(key))) return false
                    tokens++
                }
            }
            return tokens > 0
        }

        private fun safeSkinToken(group: String, key: String, value: Any?): Boolean {
            if (group == "colors" || (group == "focus" && key == "keylineColor")) {
                return value is String && isSkinColor(value)
            }
            if (group == "focus" && key == "glowAlphas") {
                val levels = value as? JSONArray ?: return false
                return levels.length() == 3 && (0 until levels.length()).all {
                    val n = levels.opt(it)
                    n is Number && n.toDouble() in 0.0..255.0
                }
            }
            if (value !is Number || !java.lang.Double.isFinite(value.toDouble())) return false
            val n = value.toDouble()
            return when (group) {
                "radii" -> n in 0.0..400.0
                "spacing" -> n in 0.0..1_200.0
                "type" -> n in 8.0..320.0
                "focus" -> when (key) {
                    "scale", "rowScale" -> n in 1.0..1.16
                    "durationMs" -> n in 0.0..500.0
                    "ringWidth", "glassPad", "glowInset", "glowInsetSm", "keylineWidth" -> n in 0.0..96.0
                    "shadowElevation" -> n in 0.0..80.0
                    "glass", "glowAlpha" -> n in 0.0..8.0
                    else -> false
                }
                else -> false
            }
        }

        private fun isSkinColor(value: String): Boolean {
            val text = value.trim()
            if (Regex("^#[0-9a-fA-F]{3}([0-9a-fA-F]{3}|[0-9a-fA-F]{5})?$").matches(text)) return true
            val match = Regex("^rgba?\\(([^)]*)\\)$", RegexOption.IGNORE_CASE).matchEntire(text) ?: return false
            val parts = match.groupValues[1].split(',').map { it.trim() }
            if (parts.size !in 3..4) return false
            if (!parts.take(3).all { it.toIntOrNull()?.let { n -> n in 0..255 } == true }) return false
            return parts.size == 3 || parts[3].toDoubleOrNull()?.let { it in 0.0..1.0 } == true
        }

        /** A missing legacy declaration meant TV-only; only an explicit `web` target is shared. */
        private fun targetsOf(arr: JSONArray?): Set<String> {
            if (arr == null) return setOf("android-tv")
            val out = LinkedHashSet<String>()
            for (i in 0 until arr.length()) {
                val target = arr.optString(i).trim()
                if (target == "android-tv" || target == "web") out.add(target)
            }
            return out.ifEmpty { setOf("android-tv") }
        }

        private val SKIN_GROUPS = listOf("colors", "radii", "spacing", "type", "focus")

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
