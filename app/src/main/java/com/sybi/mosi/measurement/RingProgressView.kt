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
import kotlin.math.min

/**
 * Vista de anillo de progreso circular para la pantalla de Oxígeno en Sangre.
 * Muestra una pista circular translúcida, un arco de progreso de color,
 * etiqueta superior, valor gigante central y subtexto o unidad.
 */
class RingProgressView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var progressRatio: Float = 0f
    private var animatedRatio: Float = 0f
    private var animator: ValueAnimator? = null

    private var labelTopText: String = ""
    private var valueText: String = "--"
    private var unitText: String = ""

    private var trackColor: Int = Color.parseColor("#BBDEFB")
    private var progressColor: Int = Color.parseColor("#1E88E5")
    private var valueTextColor: Int = Color.parseColor("#1E88E5")

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val labelTopPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#001B3A")
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val valueTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    private val ringBounds = RectF()

    fun setColors(trackClr: Int, activeClr: Int, textClr: Int = activeClr) {
        this.trackColor = trackClr
        this.progressColor = activeClr
        this.valueTextColor = textClr
        invalidate()
    }

    fun setData(topLabel: String, valStr: String, unitStr: String = "", ratio: Float, animated: Boolean = true) {
        this.labelTopText = topLabel
        this.valueText = valStr
        this.unitText = unitStr
        this.progressRatio = ratio.coerceIn(0f, 1f)

        if (animated && width > 0) {
            animator?.cancel()
            animator = ValueAnimator.ofFloat(animatedRatio, progressRatio).apply {
                duration = 600
                interpolator = DecelerateInterpolator()
                addUpdateListener { anim ->
                    this@RingProgressView.animatedRatio = anim.animatedValue as Float
                    invalidate()
                }
                start()
            }
        } else {
            this.animatedRatio = progressRatio
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return

        val cx = width / 2f
        val cy = height / 2f
        val maxRadius = min(width, height) / 2f
        val ringStrokeWidth = maxRadius * 0.18f
        val radius = maxRadius - ringStrokeWidth

        ringBounds.set(cx - radius, cy - radius, cx + radius, cy + radius)

        // Draw track circle (360 degrees)
        trackPaint.color = trackColor
        trackPaint.strokeWidth = ringStrokeWidth
        canvas.drawArc(ringBounds, -90f, 360f, false, trackPaint)

        // Draw active progress arc (-90° is top)
        if (animatedRatio > 0f) {
            progressPaint.color = progressColor
            progressPaint.strokeWidth = ringStrokeWidth
            val sweepAngle = animatedRatio * 360f
            canvas.drawArc(ringBounds, -90f, sweepAngle, false, progressPaint)
        }

        // Text sizing
        labelTopPaint.textSize = radius * 0.26f
        valueTextPaint.textSize = radius * 0.44f
        valueTextPaint.color = valueTextColor

        // Draw Label Top (e.g. "SpO2", "BPM", "PI %")
        if (labelTopText.isNotBlank()) {
            canvas.drawText(labelTopText, cx, cy - (radius * 0.28f), labelTopPaint)
        }

        // Draw Main Value Text (e.g. "98 %", "72", "5.8")
        val fullValue = if (unitText.isNotBlank()) "$valueText $unitText" else valueText
        canvas.drawText(fullValue, cx, cy + (radius * 0.16f), valueTextPaint)
    }
}
