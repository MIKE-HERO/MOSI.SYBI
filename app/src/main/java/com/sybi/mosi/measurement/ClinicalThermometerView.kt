package com.sybi.mosi.measurement

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator

/** Termómetro clínico vertical que acompaña el valor y su clasificación. */
class ClinicalThermometerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private var displayedTemperature = 34.0
    private var animator: ValueAnimator? = null
    private val tube = RectF()
    private val shellPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#DCE7EF") }
    private val innerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val fluidPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#24A36A") }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#94A8B7"); strokeWidth = 2f }

    fun setTemperature(value: Double, animated: Boolean = true) {
        val target = value.coerceIn(34.0, 42.0)
        fluidPaint.color = when {
            value < 35.0 -> Color.parseColor("#2D8CFF")
            value <= 37.5 -> Color.parseColor("#24A36A")
            value <= 39.0 -> Color.parseColor("#F4B740")
            else -> Color.parseColor("#E05252")
        }
        animator?.cancel()
        if (!animated) {
            displayedTemperature = target
            invalidate()
        } else {
            animator = ValueAnimator.ofFloat(displayedTemperature.toFloat(), target.toFloat()).apply {
                duration = 700L
                interpolator = DecelerateInterpolator()
                addUpdateListener { displayedTemperature = (it.animatedValue as Float).toDouble(); invalidate() }
                start()
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val unit = minOf(width, height).toFloat()
        val cx = width / 2f
        val bulbRadius = unit * 0.18f
        val tubeWidth = unit * 0.25f
        val tubeTop = height * 0.08f
        val bulbCy = height - bulbRadius - height * 0.06f
        val tubeBottom = bulbCy
        tube.set(cx - tubeWidth / 2f, tubeTop, cx + tubeWidth / 2f, tubeBottom)
        canvas.drawRoundRect(tube, tubeWidth / 2f, tubeWidth / 2f, shellPaint)
        val inset = tubeWidth * 0.22f
        val inner = RectF(tube.left + inset, tube.top + inset, tube.right - inset, tube.bottom)
        canvas.drawRoundRect(inner, inner.width() / 2f, inner.width() / 2f, innerPaint)
        val ratio = ((displayedTemperature - 34.0) / 8.0).toFloat().coerceIn(0f, 1f)
        val fluid = RectF(inner.left, inner.bottom - inner.height() * ratio, inner.right, inner.bottom)
        canvas.drawRoundRect(fluid, inner.width() / 2f, inner.width() / 2f, fluidPaint)
        canvas.drawCircle(cx, bulbCy, bulbRadius, shellPaint)
        canvas.drawCircle(cx, bulbCy, bulbRadius * 0.72f, fluidPaint)
        repeat(5) { index ->
            val y = tubeTop + (tubeBottom - tubeTop) * index / 4f
            canvas.drawLine(tube.right + unit * 0.04f, y, tube.right + unit * 0.13f, y, tickPaint)
        }
    }
}
