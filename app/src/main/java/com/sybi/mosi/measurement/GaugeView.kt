package com.sybi.mosi.measurement

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Vista de gráfico tipo velocímetro / arco semicircular con aguja indicadora y texto curvo centrado sobre el arco.
 * Soporta configuraciones para IMC, Temperatura, Oxígeno (SpO2) y Presión/Pulso.
 */
class GaugeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class GaugeType {
        IMC,
        TEMPERATURE,
        SPO2,
        PRESSURE
    }

    data class GaugeSegment(
        val labelTop: String,
        val labelBottom: String,
        val minVal: Double,
        val maxVal: Double,
        val color: Int
    )

    private var gaugeType: GaugeType = GaugeType.IMC
    private var segments: List<GaugeSegment> = emptyList()
    private var minGaugeVal: Double = 10.0
    private var maxGaugeVal: Double = 40.0

    private var currentValue: Double = 0.0
    private var animatedValue: Double = 0.0
    private var animator: ValueAnimator? = null

    // Paints
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.BUTT
    }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#E4EAF1")
        strokeCap = Paint.Cap.ROUND
    }
    private val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeWidth = 4f
    }
    private val textTopPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.LEFT
        isFakeBoldText = true
    }
    private val textBottomPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#111827")
        textAlign = Paint.Align.LEFT
        isFakeBoldText = true
    }
    private val needlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#0F3E82")
        style = Paint.Style.FILL_AND_STROKE
        strokeWidth = 6f
    }
    private val pivotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#0F3E82")
        style = Paint.Style.FILL
    }
    private val pivotInnerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    private val arcBounds = RectF()
    private val textBounds = RectF()
    private val pathSegment = Path()
    private val pathMeasure = PathMeasure()

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        setGaugeType(GaugeType.IMC)
    }

    fun setGaugeType(type: GaugeType) {
        this.gaugeType = type
        when (type) {
            GaugeType.IMC -> {
                minGaugeVal = 10.0
                maxGaugeVal = 40.0
                segments = listOf(
                    GaugeSegment("BAJO", "<18.5", 10.0, 18.5, Color.parseColor("#E3B814")),
                    GaugeSegment("NORMAL", "18.5-24.9", 18.5, 24.9, Color.parseColor("#32A852")),
                    GaugeSegment("SOBREPESO", "25-29.9", 24.9, 29.9, Color.parseColor("#E37214")),
                    GaugeSegment("OBESIDAD", "30+", 29.9, 40.0, Color.parseColor("#D93636"))
                )
            }
            GaugeType.TEMPERATURE -> {
                minGaugeVal = 32.0
                maxGaugeVal = 43.0
                segments = listOf(
                    GaugeSegment("HIPOTERMIA", "<35.0°", 32.0, 35.0, Color.parseColor("#1E88E5")),
                    GaugeSegment("NORMAL", "36.0-37.5°", 35.0, 38.5, Color.parseColor("#32A852")),
                    GaugeSegment("FIEBRE ALTA", "39.1-41.5°", 38.5, 41.5, Color.parseColor("#FBC02D")),
                    GaugeSegment("HIPERTERMIA", ">41.5°", 41.5, 43.0, Color.parseColor("#D32F2F"))
                )
            }
            GaugeType.SPO2 -> {
                minGaugeVal = 70.0
                maxGaugeVal = 100.0
                segments = listOf(
                    GaugeSegment("BAJO", "<90%", 70.0, 90.0, Color.parseColor("#FBC02D")),
                    GaugeSegment("NORMAL", "90-100%", 90.0, 100.0, Color.parseColor("#32A852"))
                )
            }
            GaugeType.PRESSURE -> {
                minGaugeVal = 40.0
                maxGaugeVal = 140.0
                segments = listOf(
                    GaugeSegment("LOW/CAUTION", "<60", 40.0, 60.0, Color.parseColor("#FBC02D")),
                    GaugeSegment("NORMAL", "60-100", 60.0, 100.0, Color.parseColor("#32A852")),
                    GaugeSegment("ALTAS", "100+", 100.0, 140.0, Color.parseColor("#D32F2F"))
                )
            }
        }
        invalidate()
    }

    fun setValue(value: Double, animated: Boolean = true) {
        currentValue = value
        val target = value.coerceIn(minGaugeVal, maxGaugeVal)
        if (value <= 0.0) {
            animator?.cancel()
            this.animatedValue = 0.0
            invalidate()
            return
        }

        if (animated && width > 0) {
            if (animatedValue <= 0.0) {
                animatedValue = 0.0
            }
            animator?.cancel()
            animator = ValueAnimator.ofFloat(animatedValue.toFloat(), target.toFloat()).apply {
                duration = 800
                interpolator = DecelerateInterpolator()
                addUpdateListener { anim ->
                    this@GaugeView.animatedValue = (anim.animatedValue as Float).toDouble()
                    invalidate()
                }
                start()
            }
        } else {
            animator?.cancel()
            this.animatedValue = target
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0 || segments.isEmpty()) return

        val cx = width / 2f
        val cy = height * 0.70f
        val outerRadius = min(width * 0.43f, height * 0.58f)
        val arcStrokeWidth = (outerRadius * 0.16f).coerceAtLeast(12f)

        arcPaint.strokeWidth = arcStrokeWidth
        arcPaint.strokeCap = Paint.Cap.ROUND
        trackPaint.strokeWidth = arcStrokeWidth
        val arcRadius = outerRadius - arcStrokeWidth

        arcBounds.set(
            cx - arcRadius,
            cy - arcRadius,
            cx + arcRadius,
            cy + arcRadius
        )

        val totalSweep = 180f
        val startBaseAngle = 180f

        trackPaint.color = Color.parseColor("#E8EEF3")
        trackPaint.setShadowLayer(8f, 0f, 3f, Color.parseColor("#18001B3A"))
        canvas.drawArc(arcBounds, startBaseAngle, totalSweep, false, trackPaint)
        trackPaint.clearShadowLayer()

        // Segmentos delgados, separados y sin texto curvo: se leen con mucha más claridad.
        for (seg in segments) {
            val segStartRatio = ((seg.minVal - minGaugeVal) / (maxGaugeVal - minGaugeVal)).coerceIn(0.0, 1.0)
            val segEndRatio = ((seg.maxVal - minGaugeVal) / (maxGaugeVal - minGaugeVal)).coerceIn(0.0, 1.0)

            val segStartAngle = (startBaseAngle + segStartRatio * totalSweep).toFloat()
            val segSweepAngle = ((segEndRatio - segStartRatio) * totalSweep).toFloat()

            arcPaint.color = seg.color
            val gap = if (segSweepAngle > 8f) 2.2f else 0.8f
            canvas.drawArc(arcBounds, segStartAngle + gap, segSweepAngle - gap * 2f, false, arcPaint)
        }

        // Indicador corto y discreto, con punto de lectura sobre el arco.
        val needleRatio = ((animatedValue - minGaugeVal) / (maxGaugeVal - minGaugeVal)).coerceIn(0.0, 1.0)
        val needleAngle = (startBaseAngle + needleRatio * totalSweep).toFloat()
        val needleRad = Math.toRadians(needleAngle.toDouble())

        val needleLen = arcRadius
        val nx = (cx + needleLen * cos(needleRad)).toFloat()
        val ny = (cy + needleLen * sin(needleRad)).toFloat()

        needlePaint.strokeWidth = (arcStrokeWidth * 0.28f).coerceAtLeast(4f)
        needlePaint.strokeCap = Paint.Cap.ROUND
        needlePaint.setShadowLayer(4f, 0f, 2f, Color.parseColor("#40001B3A"))
        canvas.drawLine(cx, cy, nx, ny, needlePaint)
        needlePaint.clearShadowLayer()

        val pivotR = arcStrokeWidth * 0.48f
        canvas.drawCircle(cx, cy, pivotR, pivotPaint)
        canvas.drawCircle(cx, cy, pivotR * 0.38f, pivotInnerPaint)
        canvas.drawCircle(nx, ny, arcStrokeWidth * 0.22f, pivotInnerPaint)

        val activeSegment = segments.firstOrNull { animatedValue >= it.minVal && animatedValue <= it.maxVal }
            ?: segments.last()
        val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#17384D")
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
            textSize = (outerRadius * 0.25f).coerceIn(22f, 42f)
        }
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = activeSegment.color
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
            textSize = (outerRadius * 0.12f).coerceIn(13f, 20f)
        }
        val captionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#6B7F8F")
            textAlign = Paint.Align.CENTER
            textSize = (outerRadius * 0.09f).coerceIn(11f, 16f)
        }

        val displayValue = if (currentValue > 0.0) String.format("%.1f", currentValue) else "—"
        canvas.drawText(displayValue, cx, cy + outerRadius * 0.28f, valuePaint)
        canvas.drawText(activeSegment.labelTop, cx, cy + outerRadius * 0.49f, labelPaint)
        canvas.drawText(activeSegment.labelBottom, cx, cy + outerRadius * 0.65f, captionPaint)
    }

    private fun drawCurvedText(
        canvas: Canvas,
        text: String,
        path: Path,
        pathLength: Float,
        paint: Paint,
        targetTextSize: Float,
        vOffset: Float,
        maxAllowedWidth: Float
    ) {
        if (text.isBlank() || pathLength <= 0f) return

        var textSize = targetTextSize
        paint.textSize = textSize
        var textWidth = paint.measureText(text)

        while (textWidth > maxAllowedWidth && textSize > 8f) {
            textSize -= 1f
            paint.textSize = textSize
            textWidth = paint.measureText(text)
        }

        val hOffset = ((pathLength - textWidth) / 2f).coerceAtLeast(0f)
        canvas.drawTextOnPath(text, path, hOffset, vOffset, paint)
    }
}
