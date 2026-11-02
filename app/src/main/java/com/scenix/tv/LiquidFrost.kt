package com.scenix.tv

import android.app.Activity
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.view.PixelCopy
import android.view.View
import com.scenix.tv.core.AppContext

/**
 * Real liquid glass for chrome surfaces: a FROST that samples what is actually painted behind the
 * surface, not a flat wash pretending to. The technique is ported from QWEA0/Liquid-Glass-Android
 * (MIT, https://github.com/QWEA0/Liquid-Glass-Android — iOS-26-style glassmorphism for the View
 * system): capture the backdrop region of the window, blur it, and refract it near the edges with
 * an SDF lens whose displacement follows their inverse-power profile ∝ (1 + d/k)^-p, sampled
 * inward like iOS. The library itself is not vendored — this APK deliberately ships third-party
 * free for the 2 GB Sony panel (see the app/build.gradle header) — but its pipeline is reproduced
 * at the small sizes our chrome needs, where one snapshot per scene change is affordable:
 *
 *  - API 33+: backdrop bitmap + Paint RenderEffect blur → AGSL lens chain (GPU, no per-frame cost
 *    while the scene is static);
 *  - API 31–32: backdrop bitmap + RenderEffect blur;
 *  - below 31 (the Android 11 BRAVIA floor): coarse resampling — capture at 1/7 scale, draw back
 *    up with bilinear filtering, which IS a blur at that ratio — over the pack's translucent wash.
 *
 * Honesty note: captures happen on scene change (layout, hero slide), never per frame; frost under
 * fast-scrolling content would need their continuous parent-capture pipeline. Our chrome sits over
 * the static wall and the twice-a-minute hero slide, so the snapshot path is the right trade here.
 */
class LiquidFrost private constructor(
    private val host: View,
    private val frost: FrostDrawable,
) {
    /** Re-sample what stands behind the surface (hero rotation, section rebuild). */
    fun refresh() = frost.requestCapture()

    companion object {
        /**
         * Layer a frost drawable OVER [fallback] as the host's background (the fallback keeps
         * showing until the first capture lands, and forever if PixelCopy is refused). The host
         * must already clip to its rounded outline where shape matters.
         */
        fun install(host: View, app: AppContext, radiusPx: Int, fallback: Drawable?): LiquidFrost? {
            var ctx = host.context
            while (ctx is ContextWrapper && ctx !is Activity) ctx = ctx.baseContext
            if (ctx !is Activity) return null
            val theme = app.theme
            val frost = FrostDrawable(
                host, radiusPx,
                theme.space("frostBevel", 26f).toInt(),
                theme.space("frostRefr", 14f).toFloat(),
                theme.packInt("frostFall", 3),
                theme.packInt("frostDisp", 12) / 100f,
            )
            host.background = if (fallback != null) LayerDrawable(arrayOf(fallback, frost)) else frost
            return LiquidFrost(host, frost)
        }
    }
}

private class FrostDrawable(
    private val host: View,
    private val radiusPx: Int,
    private val bevel: Int,
    private val refraction: Float,
    private val falloff: Int,
    private val dispersion: Float,
) : Drawable() {

    private var bmp: Bitmap? = null
    private var pending = false
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val clip = Path()
    /** GPU frost chain (blur → AGSL lens); applied on an offscreen RenderNode, never on the
     *  host view itself — that would blur the pill's text and icons too. */
    private var effect: android.graphics.RenderEffect? = null
    private var lens: RuntimeShader? = null
    private var node: android.graphics.RenderNode? = null
    private var nodeW = 0
    private var nodeH = 0
    private var effectTried = false

    init {
        host.addOnLayoutChangeListener { _, l, t, r, b, _, _, _, _ ->
            if (r - l > 8 && b - t > 8) requestCapture()
        }
    }

    fun requestCapture() {
        if (pending || !host.isShown) return
        pending = true
        host.post { capture() }
    }

    private fun capture() {
        var ctx = host.context
        while (ctx is ContextWrapper && ctx !is Activity) ctx = ctx.baseContext
        val window = (ctx as? Activity)?.window ?: run { pending = false; return }
        val loc = IntArray(2)
        host.getLocationInWindow(loc)
        val w = host.width; val h = host.height
        if (w < 8 || h < 8) { pending = false; return }
        // The GPU paths read near 1:1 detail; the pre-31 fallback blurs by downsampling hard.
        val scale = if (Build.VERSION.SDK_INT >= 31) 3 else 7
        val dst = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        try {
            PixelCopy.request(window, Rect(loc[0], loc[1], loc[0] + w, loc[1] + h), dst, { result ->
                pending = false
                if (result == PixelCopy.SUCCESS) {
                    val small = if (scale > 1) Bitmap.createScaledBitmap(dst,
                        maxOf(1, w / scale), maxOf(1, h / scale), true) else dst
                    if (small !== dst) dst.recycle()
                        bmp = small
                        prepareEffect()
                        invalidateSelf()
                } else {
                    dst.recycle()
                }
            }, host.handler)
        } catch (e: Exception) {
            pending = false
            dst.recycle()
        }
    }

    private fun prepareEffect() {
        if (effectTried || Build.VERSION.SDK_INT < 31) return
        effectTried = true
        try {
            val blur = RenderEffect.createBlurEffect(24f, 24f, android.graphics.Shader.TileMode.CLAMP)
            if (Build.VERSION.SDK_INT >= 33 && refraction > 0f) {
                val shader = RuntimeShader(AGSL)
                shader.setFloatUniform("rad", radiusPx.toFloat())
                shader.setFloatUniform("bevel", bevel.toFloat())
                shader.setFloatUniform("refr", refraction)
                shader.setFloatUniform("fall", falloff.toFloat())
                shader.setFloatUniform("disp", dispersion)
                lens = shader
                effect = RenderEffect.createChainEffect(blur,
                    RenderEffect.createRuntimeShaderEffect(shader, "content"))
            } else {
                effect = blur
            }
        } catch (e: Exception) {
            // A driver that refuses the GPU frost keeps the coarse-sampled bitmap.
            effect = null
        }
    }

    override fun draw(canvas: Canvas) {
        val b = bmp ?: return
        val w = bounds.width(); val h = bounds.height()
        if (w < 8 || h < 8) return
        val e = effect
        if (e != null && Build.VERSION.SDK_INT >= 31) {
            // The frost is recorded through an offscreen RenderNode so the blur+lens chain touches
            // only the sampled backdrop — never the surface's text or icons.
            try {
                var n = node
                if (n == null || nodeW != w || nodeH != h) {
                    n = android.graphics.RenderNode("scenix-frost")
                    node = n; nodeW = w; nodeH = h
                }
                n.setPosition(0, 0, w, h)
                lens?.setFloatUniform("size", w.toFloat(), h.toFloat())
                n.setRenderEffect(e)
                val rc = n.beginRecording()
                clip.reset()
                clip.addRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()),
                    radiusPx.toFloat(), radiusPx.toFloat(), Path.Direction.CW)
                rc.clipPath(clip)
                rc.drawBitmap(b, null, Rect(0, 0, w, h), paint)
                n.endRecording()
                canvas.drawRenderNode(n)
                return
            } catch (ex: Exception) {
                effect = null
            }
        }
        canvas.save()
        clip.reset()
        clip.addRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()),
            radiusPx.toFloat(), radiusPx.toFloat(), Path.Direction.CW)
        canvas.clipPath(clip)
        // Pre-31 (the Android 11 BRAVIA floor): the 1/7 sample plus bilinear upscale IS the frost.
        canvas.drawBitmap(b, null, Rect(0, 0, w, h), paint)
        canvas.restore()
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {}
    override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT

    /**
     * The lens, ported in spirit from GlassLensRenderer: a rounded-rect signed distance field,
     * displaced ALONG its gradient with an inverse-power weight so almost all the bending sits at
     * the edge (their "gravitational-lens" profile), sampled inward, with per-channel chromatic
     * dispersion. Uniform names match the setters above.
     */
    private companion object {
        val AGSL = """
            uniform shader content;
            uniform float2 size;
            uniform float rad;
            uniform float bevel;
            uniform float refr;
            uniform float fall;
            uniform float disp;

            float sdRR(float2 c, float2 b, float r) {
                float2 q = abs(c) - b + r;
                return min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - r;
            }

            half4 main(float2 xy) {
                float2 c = xy - 0.5 * size;
                float2 b = 0.5 * size;
                float d = sdRR(c, b, rad);
                float e = 1.5;
                float gx = sdRR(c + float2(e, 0.0), b, rad) - sdRR(c - float2(e, 0.0), b, rad);
                float gy = sdRR(c + float2(0.0, e), b, rad) - sdRR(c - float2(0.0, e), b, rad);
                float2 g = (abs(gx) + abs(gy) < 0.001) ? float2(0.0) : normalize(float2(gx, gy));
                float din = max(-d, 0.0);
                float w = refr * pow(1.0 + din / max(bevel * 0.25, 1.0), -fall);
                if (disp <= 0.0) return content.eval(xy + g * w);
                half4 rr = content.eval(xy + g * w * (1.0 + disp));
                half4 bb = content.eval(xy + g * w * (1.0 - disp));
                half4 base = content.eval(xy + g * w);
                return half4(rr.r, base.g, bb.b, base.a);
            }
        """
    }
}
