package com.scenix.tv

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.view.View
import android.view.animation.PathInterpolator

/**
 * A short, one-shot wordmark reveal for the launch surface. The logo is drawn from the same vector
 * used everywhere else; only the narrow leading strip is skewed and shaded, so it reads as a sheet
 * unrolling rather than as a replacement logo. Keeping it in a View also leaves Android's initial
 * window drawable instant and reliable during a cold start.
 */
class ScenixWordmarkIntroView(context: Context) : View(context) {
    private val wordmark: Drawable = checkNotNull(context.getDrawable(R.drawable.scenix_wordmark)).mutate()
    private val foldMask = Path()
    private val foldPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(205, 255, 96, 101)
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private var reveal = 0f
    private var intro: ValueAnimator? = null

    init {
        // The mark is redundant with the activity label and carries no action of its own.
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!ValueAnimator.areAnimatorsEnabled()) {
            reveal = 1f
            invalidate()
            return
        }
        intro = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = INTRO_DURATION_MS
            startDelay = INTRO_DELAY_MS
            interpolator = PathInterpolator(0.22f, 0.74f, 0.28f, 1f)
            addUpdateListener {
                reveal = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        intro?.cancel()
        intro = null
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return

        wordmark.setBounds(0, 0, width, height)
        if (reveal >= 1f) {
            wordmark.draw(canvas)
            return
        }

        val fullWidth = width.toFloat()
        val fullHeight = height.toFloat()
        val leadingEdge = fullWidth * reveal
        val foldWidth = (fullWidth * FOLD_WIDTH_RATIO).coerceAtLeast(fullHeight * 0.9f)
        val settledRight = (leadingEdge - foldWidth).coerceIn(0f, fullWidth)

        // The stable part lands first; this is what remains after the curl moves past it.
        if (settledRight > 0f) {
            val settled = canvas.save()
            canvas.clipRect(0f, 0f, settledRight, fullHeight)
            wordmark.draw(canvas)
            canvas.restoreToCount(settled)
        }

        if (leadingEdge <= 0f) return
        val foldLeft = (leadingEdge - foldWidth).coerceAtLeast(0f)
        val foldRight = leadingEdge.coerceAtMost(fullWidth)
        val slant = (fullHeight * 0.17f).coerceAtMost(foldWidth * 0.42f)

        // A trapezoid and a slight shear turn the leading segment into a curled ribbon. This costs
        // one drawable pass for less than a second, then the settled vector is all that remains.
        foldMask.reset()
        foldMask.moveTo(foldLeft + slant, 0f)
        foldMask.lineTo(foldRight, 0f)
        foldMask.lineTo((foldRight - slant).coerceAtLeast(foldLeft), fullHeight)
        foldMask.lineTo(foldLeft, fullHeight)
        foldMask.close()

        val folded = canvas.save()
        canvas.clipPath(foldMask)
        val logo = canvas.save()
        val pivotX = foldLeft + foldWidth * 0.5f
        canvas.translate(pivotX, 0f)
        canvas.skew(-0.17f, 0f)
        canvas.translate(-pivotX, 0f)
        wordmark.draw(canvas)
        canvas.restoreToCount(logo)

        foldPaint.shader = LinearGradient(
            foldLeft,
            0f,
            foldRight,
            fullHeight,
            intArrayOf(
                Color.argb(16, 255, 255, 255),
                Color.argb(112, 72, 0, 5),
                Color.argb(28, 255, 255, 255),
                Color.argb(148, 133, 0, 8),
            ),
            floatArrayOf(0f, 0.38f, 0.7f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawPath(foldMask, foldPaint)
        foldPaint.shader = null
        canvas.restoreToCount(folded)

        edgePaint.strokeWidth = (fullHeight * 0.035f).coerceAtLeast(2f)
        canvas.drawLine(foldRight, 0f, (foldRight - slant).coerceAtLeast(0f), fullHeight, edgePaint)
    }

    private companion object {
        const val INTRO_DELAY_MS = 70L
        const val INTRO_DURATION_MS = 720L
        const val FOLD_WIDTH_RATIO = 0.18f
    }
}
