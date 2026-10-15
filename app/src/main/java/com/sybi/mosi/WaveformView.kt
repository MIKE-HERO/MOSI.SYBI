package com.sybi.mosi

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import kotlin.math.max

/** Monitor pletismográfico en vivo alimentado por las muestras reales del oxímetro. */
class WaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density
    private val points = ArrayDeque<Int>()
    private val maxPoints = 240
    private val path = Path()

    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val minorGridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#163B5268")
        strokeWidth = 1f
    }
    private val majorGridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2B5C7891")
        strokeWidth = 1.2f * density
    }
    private val waveGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#5529D3C2")
        style = Paint.Style.STROKE
        strokeWidth = 7f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#24D6C5")
        style = Paint.Style.STROKE
        strokeWidth = 2.2f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#A8C0CF")
        textSize = 11f * resources.displayMetrics.scaledDensity
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
    }
    private val livePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#36E0A2")
        textSize = 10f * resources.displayMetrics.scaledDensity
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    private val emptyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#7894A5")
        textSize = 13f * resources.displayMetrics.scaledDensity
        textAlign = Paint.Align.CENTER
    }

    fun setLineColor(color: Int) {
        // Conserva la identidad del tema sin perder el contraste de monitor clínico.
        wavePaint.color = color
        waveGlowPaint.color = Color.argb(70, Color.red(color), Color.green(color), Color.blue(color))
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        backgroundPaint.shader = LinearGradient(0f, 0f, 0f, h, Color.parseColor("#102A3A"), Color.parseColor("#071A27"), Shader.TileMode.CLAMP)
        canvas.drawRoundRect(0f, 0f, w, h, 16f * density, 16f * density, backgroundPaint)

        val left = 16f * density
        val right = w - 16f * density
        val top = 32f * density
        val bottom = h - 16f * density
        drawGrid(canvas, left, top, right, bottom)
        canvas.drawText("PLETISMOGRAFÍA  •  SEÑAL EN TIEMPO REAL", left, 20f * density, labelPaint)
        livePaint.textAlign = Paint.Align.RIGHT
        canvas.drawCircle(right - 42f * density, 16.5f * density, 3.5f * density, livePaint)
        canvas.drawText("LIVE", right, 20f * density, livePaint)

        if (points.size < 2) {
            canvas.drawText("Esperando señal del oxímetro…", w / 2f, (top + bottom) / 2f, emptyPaint)
            return
        }

        val snapshot = points.toList()
        val rawMin = snapshot.minOrNull() ?: 0
        val rawMax = snapshot.maxOrNull() ?: 255
        val padding = max(6f, (rawMax - rawMin) * 0.12f)
        val minValue = rawMin - padding
        val range = max(1f, rawMax - rawMin + padding * 2f)
        val stepX = (right - left) / (maxPoints - 1).toFloat()
        val startX = right - (snapshot.size - 1) * stepX

        path.reset()
        snapshot.forEachIndexed { index, value ->
            val x = startX + index * stepX
            val normalized = ((value - minValue) / range).coerceIn(0f, 1f)
            val y = bottom - normalized * (bottom - top)
            if (index == 0) path.moveTo(x, y) else {
                val previousX = startX + (index - 1) * stepX
                path.quadTo((previousX + x) / 2f, y, x, y)
            }
        }
        canvas.drawPath(path, waveGlowPaint)
        canvas.drawPath(path, wavePaint)
    }

    private fun drawGrid(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float) {
        val small = 12f * density
        var x = left
        var index = 0
        while (x <= right) {
            canvas.drawLine(x, top, x, bottom, if (index % 5 == 0) majorGridPaint else minorGridPaint)
            x += small
            index++
        }
        var y = top
        index = 0
        while (y <= bottom) {
            canvas.drawLine(left, y, right, y, if (index % 5 == 0) majorGridPaint else minorGridPaint)
            y += small
            index++
        }
    }

    fun addPoint(value: Int) {
        if (points.size >= maxPoints) points.removeFirst()
        points.addLast(value.coerceIn(0, 255))
        postInvalidateOnAnimation()
    }

    fun clear() {
        points.clear()
        invalidate()
    }
}
