package com.sybi.mosi.measurement

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import kotlin.math.max
import kotlin.math.min

/**
 * Gráfica de tendencia de línea para el informe de salud (estilo del PDF MediTech):
 * conecta en orden cronológico los valores de las últimas mediciones, con un punto en
 * cada una y el número encima. Pensada para el equipo de 2GB: dibujo directo en Canvas,
 * sin librerías ni bitmaps intermedios.
 *
 * Los datos se pasan ya ordenados de más antiguo (izquierda) a más reciente (derecha).
 */
class LineChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    data class Punto(val valor: Float, val etiquetaX: String)

    private var puntos: List<Punto> = emptyList()
    private var lineColor: Int = Color.parseColor("#0F3E82")

    private val d = resources.displayMetrics.density

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * d
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#111827")
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
        textSize = 9f * d
    }
    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#9E9E9E")
        textAlign = Paint.Align.CENTER
        textSize = 8f * d
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#EEEEEE")
        style = Paint.Style.STROKE
        strokeWidth = 1f * d
    }

    private val path = Path()

    /** Formato opcional del valor que se dibuja encima de cada punto (default: 1 decimal, sin .0). */
    var formatoValor: (Float) -> String = { v ->
        if (v == v.toLong().toFloat()) v.toLong().toString() else "%.1f".format(v)
    }

    fun setLineColor(color: Int) {
        lineColor = color
        invalidate()
    }

    fun setData(datos: List<Punto>) {
        this.puntos = datos
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0 || puntos.isEmpty()) return

        val padTop = 16f * d       // espacio para el valor encima del punto más alto
        val padBottom = 16f * d    // espacio para las etiquetas del eje X
        val padSide = 18f * d
        val plotW = width - padSide * 2
        val plotH = height - padTop - padBottom
        if (plotW <= 0 || plotH <= 0) return

        var minV = Float.MAX_VALUE
        var maxV = -Float.MAX_VALUE
        for (p in puntos) { minV = min(minV, p.valor); maxV = max(maxV, p.valor) }
        // Margen vertical del 12% para que la línea no toque los bordes; evita rango 0.
        val rango = (maxV - minV).let { if (it < 0.0001f) 1f else it }
        minV -= rango * 0.12f
        maxV += rango * 0.12f
        val rangoFinal = maxV - minV

        // Línea base del grid (abajo)
        canvas.drawLine(padSide, padTop + plotH, padSide + plotW, padTop + plotH, gridPaint)

        fun xDe(i: Int): Float =
            if (puntos.size == 1) padSide + plotW / 2f
            else padSide + plotW * i / (puntos.size - 1).toFloat()

        fun yDe(v: Float): Float = padTop + plotH - ((v - minV) / rangoFinal) * plotH

        // Línea
        linePaint.color = lineColor
        path.reset()
        puntos.forEachIndexed { i, p ->
            val x = xDe(i); val y = yDe(p.valor)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        canvas.drawPath(path, linePaint)

        // Puntos + valores + etiquetas X
        dotPaint.color = lineColor
        val mostrarCadaX = max(1, puntos.size / 6) // no saturar el eje X si hay muchas
        puntos.forEachIndexed { i, p ->
            val x = xDe(i); val y = yDe(p.valor)
            canvas.drawCircle(x, y, 2.6f * d, dotPaint)
            // Valor encima (solo primero, último y los extremos visibles para no amontonar)
            val esClave = i == 0 || i == puntos.size - 1 || i % mostrarCadaX == 0
            if (esClave) {
                canvas.drawText(formatoValor(p.valor), x, y - 5f * d, valuePaint)
            }
            // Etiqueta del eje X
            if (i % mostrarCadaX == 0 || i == puntos.size - 1) {
                canvas.drawText(p.etiquetaX, x, height - 3f * d, axisPaint)
            }
        }
    }
}
