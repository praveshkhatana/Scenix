package com.scenix.tv

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.view.View
import com.example.liquidglass.BlurMethod
import com.example.liquidglass.ChromaticAberrationEffect
import com.example.liquidglass.GlassAccessibilityMode
import com.example.liquidglass.GlassMaterial
import com.example.liquidglass.LiquidGlassView
import com.scenix.tv.core.AppContext

/**
 * The Liquid Glass pack's material, in one place.
 *
 * A glass surface here is a LENS: it bends and blurs what stands behind it, so a pill, a chip or a
 * plate reads as thick polished glass with a lit edge rather than as a translucent rectangle with a
 * hairline drawn on it. The lens is QWEA0/Liquid-Glass-Android (MIT), the same library the first
 * frost in this APK was ported from — the hand-written port is gone, because a hand-drawn lens
 * cannot follow a corner radius, fold the rim back on itself and split the colour channels the way
 * the material does.
 *
 * The library picks its own tier and the same pack tokens drive both of them:
 *  - Android 13+ (the emulator, newer panels): a rounded-rect SDF bends the backdrop inside the rim,
 *    with a spectral fringe — `frostBevel`, `frostRefr`, `frostFall`, `frostDisp`.
 *  - Android 11 (this BRAVIA's floor): the NEON backdrop blur plus channel split and rim light —
 *    `frostBlur`, `frostSat`, `frostAberr`, `frostDisplace`, `frostEdge`, `frostEdgeAlpha` — and a
 *    backdrop it samples from the pack's own wall rather than from a repaint of the whole page
 *    (`frostCapture`, `frostCpuScale`), because on a 2 GB panel the repaint cost a second per lens.
 *
 * Nothing in here is a colour or a size of its own: every number is a pack token, so a pack that
 * wants real glass on its chrome declares the tokens instead of editing this file.
 */
internal object LiquidGlassKit {

    /** True when this pack's surfaces should be lenses. Other packs keep their drawable fills. */
    fun isGlass(app: AppContext): Boolean =
        app.theme.packString("tileMaterial", "") == "glass" || app.theme.cardStyle == "glass"

    /** An empty glass container, sized by whatever is put inside it. */
    fun lens(context: Context, app: AppContext, radiusPx: Int): LiquidGlassView =
        GlassLens(context).registered().apply { tune(app, this, radiusPx) }

    /**
     * A lens that can stand still. While a page is scrolling, every lens on it would otherwise
     * recapture and refract its backdrop every frame — on the 2 GB Android 11 panel a shelf chip's
     * lens costs 30-40 ms per frame, and seven chips plus the nav pill is most of the frame budget
     * spent on refraction nobody is looking at mid-fling. [frozen] takes the current pixels once and
     * replays them until the page settles, exactly the rule the focused tile's slab already follows.
     */
    internal class GlassLens(context: Context) : LiquidGlassView(context) {
        var frozen: android.graphics.Bitmap? = null

        override fun draw(canvas: android.graphics.Canvas) {
            val f = frozen
            if (f != null && !f.isRecycled && f.width == width && f.height == height) {
                canvas.drawBitmap(f, 0f, 0f, null)
                return
            }
            super.draw(canvas)
        }

        fun registered(): GlassLens {
            synchronized(live) {
                live.removeAll { e -> e.get() == null }
                live.add(java.lang.ref.WeakReference(this))
            }
            return this
        }
    }

    private val live = mutableListOf<java.lang.ref.WeakReference<GlassLens>>()

    /**
     * Hold every lens at its current pixels while the page moves ([on] true), and let them refract
     * again the moment it settles. The freeze costs one honest render per lens at the start of the
     * gesture and nothing per frame after; unfreezing redraws each lens once, so a page that stops
     * mid-fling shows full glass on the next frame. CPU tier only — the API 33 shader tier renders
     * its lenses on the GPU and can afford to keep refracting.
     */
    fun scrollFreeze(on: Boolean) {
        if (Build.VERSION.SDK_INT >= 33) return
        synchronized(live) {
            for (ref in live) {
                val g = ref.get() ?: continue
                if (on) {
                    if (g.width > 0 && g.height > 0) {
                        val b = android.graphics.Bitmap.createBitmap(
                            g.width, g.height, android.graphics.Bitmap.Config.ARGB_8888,
                        )
                        g.draw(android.graphics.Canvas(b))
                        g.frozen = b
                    }
                } else if (g.frozen != null) {
                    g.frozen = null
                    g.invalidate()
                }
            }
        }
    }

    /**
     * Puts [content] inside the pack's glass and hands back the glass. The content keeps its own
     * padding and children; its flat fill is dropped, because the lens IS the fill. The focus graph
     * stays on the content — the ring and the lift belong to what the remote selects, not to the
     * material behind it.
     */
    fun wrap(app: AppContext, content: View, radiusPx: Int): LiquidGlassView {
        val glass = lens(content.context, app, radiusPx)
        (content.parent as? android.view.ViewGroup)?.removeView(content)
        content.background = null
        glass.addView(content)
        return glass
    }

    /**
     * Every glass surface in this app floats on the page's wall, not on a sibling that paints it, so
     * each lens is told what to read: the activity's content view, once the lens is in a window.
     */
    private fun pageBehind(glass: LiquidGlassView) {
        var ctx = glass.context
        while (ctx is ContextWrapper && ctx !is Activity) ctx = ctx.baseContext
        (ctx as? Activity)?.findViewById<View>(android.R.id.content)?.let { source ->
            glass.backdropSource = source
        }
    }

    /**
     * A glass that bends [art] — the artwork under it — instead of the page wall. This is the
     * focused-tile slab: a sheet of glass resting on the selected poster, so the poster's own colours
     * refract at its rim.
     *
     * Android 13+ takes the fast path: the library snapshots [art] with its shader. The Android 11
     * panel cannot, so the lens asks us for its backdrop and we draw only [art] into the patch it
     * names — one poster, not a repaint of the page. A slab is normally shared between tiles, so the
     * view it reads is held in a box: [rebind] points it at the newly focused artwork.
     */
    fun viewLens(
        host: View,
        app: AppContext,
        radiusPx: Int,
        token: String,
        behind: Boolean = true,
    ): LiquidGlassView {
        val glass = GlassLens(host.context).registered()
        tune(app, glass, radiusPx, token, behind)
        apps[glass] = app
        return glass
    }

    /** Points a tile lens at the artwork it should now bend, and re-samples it. */
    fun rebind(glass: LiquidGlassView, art: View) {
        val app = apps[glass] ?: return
        boxes.getOrPut(glass) { arrayOfNulls(1) }[0] = art
        glass.backdropSource = art
        if (Build.VERSION.SDK_INT < 33 &&
            app.theme.packString("frostCapture", "wall") != "full") {
            // The blur tier: we draw the artwork into the patch the lens names, instead of letting
            // the library re-draw the whole page behind it.
            glass.setCustomBackdropCapture { rect ->
                val art = boxes[glass]?.get(0)
                if (art != null && art.width > 0) artPatch(app, glass, art, rect)
                else wallPatch(app, glass, rect)
            }
        }
        glass.invalidate()
    }

    /** The artwork a tile lens is currently bending, and the pack it was tuned with. */
    private val boxes = java.util.WeakHashMap<LiquidGlassView, Array<View?>>()
    private val apps = java.util.WeakHashMap<LiquidGlassView, AppContext>()

    /** The page's wall, rasterised once at page size and cut to the patch a lens asked for. */
    private fun wallPatch(
        app: AppContext,
        glass: LiquidGlassView,
        rect: android.graphics.RectF,
    ): android.graphics.Bitmap {  // glass supplies the page the wall covers
        val page = glass.backdropSource
        val pw = page?.width ?: Math.max(1, Math.ceil(rect.width().toDouble()).toInt())
        val ph = page?.height ?: Math.max(1, Math.ceil(rect.height().toDouble()).toInt())
        return patchOf(app, glass, rect, page) { canvas ->
            canvas.drawBitmap(GlassKit.wallRaster(app, pw, ph), -rect.left, -rect.top, null)
        }
    }

    /** The artwork a tile lens asked for, drawn into exactly the patch it named. */
    private fun artPatch(
        app: AppContext,
        glass: LiquidGlassView,
        art: View,
        rect: android.graphics.RectF,
    ): android.graphics.Bitmap = patchOf(app, glass, rect, art) { canvas ->
        val where = IntArray(2)
        art.getLocationInWindow(where)
        // The lens asks in window coordinates; translate so the view draws its own patch.
        canvas.translate(where[0].toFloat() - rect.left, where[1].toFloat() - rect.top)
        art.draw(canvas)
    }

    /**
     * What sits under a lens, painted into the rectangle it named.
     *
     * Two rules, each learned from a measurement on the 2 GB Android 11 panel:
     *
     *  - What the library is handed is never what is kept. The library takes ownership of the
     *    backdrop it is given and recycles it, so a patch kept for the next frame came back recycled
     *    on the frame after — `Can't copy a recycled bitmap`. The patch is painted into a scratch
     *    bitmap this kit owns and only ever copied outward.
     *  - An unchanged backdrop is not painted twice. One focus move made the tile's lens ask four to
     *    seven times for the same rectangle of the same poster, each ask costing 3-18 ms of drawing;
     *    the chrome's lenses asked 60 times for wall during a nine-button sweep. The paint is redone
     *    when a different view, or no view, is under the lens, and after [PATCH_AGAIN_MS] — so
     *    artwork that finishes loading is picked up, while a focus move pays for one paint.
     */
    private fun patchOf(
        app: AppContext,
        glass: LiquidGlassView,
        rect: android.graphics.RectF,
        under: View?,
        paint: (android.graphics.Canvas) -> Unit,
    ): android.graphics.Bitmap {
        val now = android.os.SystemClock.uptimeMillis()
        val w = Math.max(1, Math.ceil(rect.width().toDouble()).toInt())
        val h = Math.max(1, Math.ceil(rect.height().toDouble()).toInt())
        val own0 = scratch[glass]
        val same = patches[glass] != null && patches[glass]!!.under === under &&
            patches[glass]!!.rect == rect && now - patches[glass]!!.at <= PATCH_AGAIN_MS &&
            own0 != null && !own0.isRecycled && own0.width == w && own0.height == h
        var own = own0
        if (!same) {
            if (own == null || own.isRecycled || own.width != w || own.height != h) {
                own = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
                scratch[glass] = own
            }
            val canvas = android.graphics.Canvas(own)
            canvas.drawColor(0, android.graphics.PorterDuff.Mode.CLEAR)
            paint(canvas)
            patches[glass] = Patch(under, android.graphics.RectF(rect), now)
        } else {
            patches[glass]!!.at = now
        }
        // The library owns what it is given and recycles it, so it is always a copy: the scratch
        // stays ours for the next ask.
        return scratch[glass]!!.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
    }

    /** What a lens's patch currently holds, so an unchanged backdrop is not painted again. */
    private class Patch(val under: View?, val rect: android.graphics.RectF, var at: Long)

    private val patches = java.util.WeakHashMap<LiquidGlassView, Patch>()
    private val scratch = java.util.WeakHashMap<LiquidGlassView, android.graphics.Bitmap>()

    /** How long a lens may treat what is under it as unchanged. Long enough for one focus move's
     *  repeated asks, short enough that artwork which arrives late is still painted. */
    private const val PATCH_AGAIN_MS = 220L

    /**
     * The pack's glass, from tokens. Called for every lens the pack builds.
     *
     * [token] picks which token family this lens reads: the chrome lenses read `frost*`, the
     * focused-tile slab reads `focusGlass*` and falls back to `frost*`, so a pack tunes one family
     * and the other inherits it unless it says otherwise. [behind] is false for a lens that was told
     * its own backdrop (the artwork under a tile lens) instead of the page wall.
     */
    fun tune(
        app: AppContext,
        glass: LiquidGlassView,
        radiusPx: Int,
        token: String = "frost",
        behind: Boolean = true,
    ) {
        val theme = app.theme
        // A lens names its own token family; the focused-tile slab's numbers fall back to the
        // chrome's, so a pack tunes `frost*` once and every lens inherits it.
        fun pint(key: String, fallback: Int): Int =
            theme.packInt(token + key, theme.packInt("frost" + key, fallback))
        fun pspace(key: String, fallback: Float): Float =
            theme.space(token + key, theme.space("frost" + key, fallback).toFloat()).toFloat()
        fun pcolor(key: String, fallback: String): Int =
            theme.colorOr(token + key, theme.color("frost" + key, fallback))
        glass.cornerRadius = radiusPx.toFloat()

        // Which of the library's tiers this panel runs, said out loud rather than left to its
        // defaults. Below API 33 there is no shader, and on this Android 11 BRAVIA the library's
        // middle tier reaches for a framework blur hook (`EnhancedBlurEffect`) the panel does not
        // actually implement — it logged `Failed to capture backdrop: Index: 4, Size: 0` on every
        // frame of every lens and painted nothing, so the glass came out a flat plate. Asking for
        // the CPU tier instead (NEON gaussian from `libnativegauss.so`, packaged for both of this
        // APK's ABIs) is what puts the material on the television.
        val lens = Build.VERSION.SDK_INT >= 33
        glass.useShaderPipeline = lens
        glass.useHardwareBlurWhenPossible = lens
        glass.blurMethod = if (lens) BlurMethod.SMART else BlurMethod.IIR_GAUSSIAN_NEON
        glass.chromaticAberrationMode = if (lens)
            ChromaticAberrationEffect.PerformanceMode.AUTO
        else ChromaticAberrationEffect.PerformanceMode.CPP

        // The lens tier (API 33+): a band of glass `Bevel` px thick sits at the edge; the backdrop
        // under that band is bent inward by `Refr` px, piling up toward the rim at the exponent
        // `Fall`, and the three channels bend by slightly different amounts (`Disp` %), which is what
        // puts the faint spectral line on a glass edge. `Outward` flips the bend the other way, so
        // the artwork under a tile slab magnifies at the rim instead of squeezing in.
        glass.material = if (theme.packString("${token}Material",
                theme.packString("frostMaterial", "regular")) == "clear"
        ) GlassMaterial.CLEAR else GlassMaterial.REGULAR
        glass.bevelWidth = pspace("Bevel", 26f)
        glass.refractionHeight = pspace("Refr", 34f)
        glass.refractionFalloff = pint("Fall", 3).coerceIn(0, 4).toFloat()
        glass.refractionOutward = pint("Outward", 0) != 0
        glass.dispersionStrength = pint("Disp", 12) / 100f

        // The CPU tier's own knobs — the whole material on this Android 11 television, since it has
        // no shader. Backdrop blur and vibrancy first, then the channel split and the edge
        // displacement that bend the wall at the rim, then the rim light that makes the edge read as
        // the lip of a solid object. The numbers are the library's own units: `Blur` is the radius as
        // a fraction of the surface's size, `Sat` a percentage, `Edge` the rim width in tenths of a
        // pixel and `EdgeAlpha` 0-100.
        // How much frost a lens may take. On the shader tier that is free, so every lens frosts at
        // `frostBlur`/`focusGlassBlur`. On the blur tier it is not: measured on the Android 11 panel,
        // a chrome lens's blur is ~0 ms because the wall under it never changes and the library
        // re-uses that work, while a tile slab's costs 41-90 ms per render, because its backdrop
        // changes with every focus move. So the panel reads `<token>CpuBlur` instead, and the pack
        // asks the slab for none there — the refraction, rim light and tint carry the material.
        val blur = if (lens) pint("Blur", 6) else theme.packInt("${token}CpuBlur", pint("Blur", 6))
        glass.enableBackdropBlur = blur != 0
        glass.blurAmount = blur / 100f
        glass.saturation = pint("Sat", 132).toFloat()
        glass.enableChromaticAberration = pint("Aberr", 22) != 0
        glass.aberrationIntensity = pint("Aberr", 22) / 10f
        glass.displacementScale = pint("Displace", 70).toFloat()
        glass.enableEdgeHighlight = true
        glass.edgeHighlightBorderWidth = pint("Edge", 15) / 10f
        glass.edgeHighlightOpacity = pint("EdgeAlpha", 55).toFloat()

        // The surface already casts its shadow from view elevation (and the focus lift rides on that
        // same elevation), so the lens must not draw a second one under it.
        glass.enableShadow = false
        // The glass's own colour. The alpha in the token IS the strength, exactly as the library
        // counts it, so the tint token reads like every other colour token in this pack.
        glass.glassTint = pcolor("Tint", "rgba(12,22,34,0.46)")
        // The library's own accessibility tier: high contrast, battery saver or "remove animations"
        // turn the lens into an opaque plate instead of taking the picture away.
        glass.accessibilityMode = GlassAccessibilityMode.AUTO

        // The frame budget, per tier. A lens costs a capture of what is under it plus a blur of that
        // capture, and Home wears ten of them — pill, profile pill, gear and seven shelf chips. On
        // the shader tier that is one GPU pass, so the glass follows the page live at whatever scale
        // the pack sets. On a 2 GB Android 11 panel it is CPU work, and measured there it was not
        // affordable: the library's own capture re-drew the whole window for each lens (484 ms for
        // the nav pill, 100-400 ms per chip) and re-ran on every redraw, while lenses that
        // re-sampled every frame pushed the heap to 94 MB with 2-second GC pauses and 1.1-second
        // frames. So the blur tier samples once, samples only what it needs, and works coarser.
        glass.enableDynamicBackground = lens
        glass.downsampleScale = (theme.packInt("frostDownsample", 2) + if (lens) 0 else 1)
            .coerceIn(1, 4)
        glass.highQualityBlur = lens
        if (!lens) {
            glass.globalDownsampleFactor = pint("CpuScale", 2).coerceIn(1, 4).toFloat()
            // The colour split can run coarser than the rest of the material. It is the most
            // expensive pass on this tier — measured at ~87 ms over a poster-sized lens at the same
            // scale as everything else — and in the reference it is a soft fringe at the rim, which
            // survives being sampled a little coarser. `<token>AberrScale` lets a pack buy it back
            // where it cannot afford it at full quality.
            glass.aberrationDownsample = pint("AberrScale",
                pint("CpuScale", 2)).coerceIn(1, 8).toFloat()
            glass.dispersionDownsample = glass.globalDownsampleFactor
            if (behind) {
                // `frostCapture` says where a lens on this tier gets what it bends:
                //  - `wall` (the pack's choice): the lens asks US, and we draw the pack's wall into
                //    the patch it asked for — 353x167 px of gradient and bokeh instead of 1920x1080
                //    of everything, with no artwork decode in the middle. This pack's chrome stands
                //    on that wall, so it is exactly what the lens would have sampled anyway.
                //  - `region`: the library's capture, limited to the lens's own rectangle.
                //  - `full`: the library's capture of the whole window, as the first pass did.
                when (theme.packString("frostCapture", "wall")) {
                    "full" -> glass.enableOptimizedCapture = false
                    "region" -> glass.enableOptimizedCapture = true
                    else -> glass.setCustomBackdropCapture { rect -> wallPatch(app, glass, rect) }
                }
            }
        }

        if (behind) {
            pageBehind(glass)
            // A lens built before it is attached to a window has no page to read yet.
            glass.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) { pageBehind(glass) }
                override fun onViewDetachedFromWindow(v: View) {}
            })
        }

        // `frostDebug: 1` makes the pack report its own cost: the lens's size, the region it
        // processes and the milliseconds each stage takes. Put the token in the pack, read
        // `logcat -s ScenixGlass`, take it out again — this is how the budget above was measured on
        // the 2 GB panel instead of guessed at.
        if (theme.packInt("frostDebug", 0) != 0) {
            glass.collectFrameStats = true
            var last = 0L
            glass.frameStatsListener = { s ->
                val now = android.os.SystemClock.uptimeMillis()
                if (now - last > 2000L) {
                    last = now
                    android.util.Log.i("ScenixGlass",
                        "${glass.width}x${glass.height} ${s.effectName} " +
                            "px=${s.processedWidth}x${s.processedHeight} " +
                            "cap=${s.captureMs} blur=${s.blurMs} fx=${s.effectMs} " +
                            "fin=${s.finalizeMs} total=${s.totalMs} fps=${s.drawFps}")
                }
            }
        }
    }
}
