package com.sybi.mosi.measurement

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator

/**
 * Indicador clínico de espera. Conserva el nombre histórico para no romper los layouts,
 * pero reemplaza las barras tipo ecualizador por un anillo fino y sobrio.
 */
class EqualizerLoaderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var accentColor = Color.parseColor("#0F5FA8")
    private var rotationDegrees = 0f
    private val bounds = RectF()

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = Color.parseColor("#DCE7EF")
    }
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = accentColor
    }
    private val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = accentColor
    }

    private val animator = ValueAnimator.ofFloat(0f, 360f).apply {
        duration = 1250L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            rotationDegrees = it.animatedValue as Float
            invalidate()
        }
    }

    fun setBarColor(color: Int) {
        accentColor = color
        progressPaint.color = color
        centerPaint.color = color
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val size = minOf(width, height).toFloat()
        if (size <= 0f) return

        val stroke = (size * 0.105f).coerceAtLeast(2.5f)
        val inset = stroke * 1.5f
        bounds.set(inset, inset, width - inset, height - inset)
        trackPaint.strokeWidth = stroke
        progressPaint.strokeWidth = stroke

        canvas.drawArc(bounds, 0f, 360f, false, trackPaint)
        canvas.drawArc(bounds, rotationDegrees - 90f, 92f, false, progressPaint)
        canvas.drawCircle(width / 2f, height / 2f, size * 0.075f, centerPaint)
    }

    private fun startAnimation() {
        if (!animator.isStarted) animator.start()
    }

    private fun stopAnimation() {
        animator.cancel()
        rotationDegrees = 0f
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (visibility == VISIBLE) startAnimation()
    }

    override fun onDetachedFromWindow() {
        stopAnimation()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE && isAttachedToWindow) startAnimation() else stopAnimation()
    }
}
