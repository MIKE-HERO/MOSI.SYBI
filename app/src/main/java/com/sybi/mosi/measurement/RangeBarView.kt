package com.sybi.mosi.measurement

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.max

/**
 * Barra de rango horizontal con un pin flotante que marca el valor, como las del informe
 * MediTech (IMC, tasa de grasa, grasa visceral, temperatura, SpO2). La barra se divide en
 * segmentos de color; debajo van las etiquetas de cada segmento; arriba, un "globo" con el
 * valor apuntando a su posición. Dibujo directo en Canvas (equipo de 2GB).
 */
class RangeBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    data class Segmento(val etiqueta: String, val min: Double, val max: Double, val color: Int)

    private var segmentos: List<Segmento> = emptyList()
    private var valor: Double = 0.0
    private var sufijo: String = ""
    private var minTotal = 0.0
    private var maxTotal = 1.0

    private val d = resources.displayMetrics.density

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#6B7280")
        textAlign = Paint.Align.CENTER
        textSize = 9f * d
    }
    private val pinPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val pinTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
        textSize = 11f * d
    }

    private val barRect = RectF()
    private val pinRect = RectF()
    private val pinPath = Path()

    /** Define la barra. Los segmentos deben venir en orden y ser contiguos. */
    fun setSegmentos(lista: List<Segmento>) {
        segmentos = lista
        if (lista.isNotEmpty()) {
            minTotal = lista.first().min
            maxTotal = lista.last().max
        }
        invalidate()
    }

    fun setValor(v: Double, sufijo: String = "") {
        valor = v
        this.sufijo = sufijo
        invalidate()
    }

    /** Color del pin según en qué segmento cae el valor (el color de esa zona). */
    private fun colorDelValor(): Int {
        for (s in segmentos) if (valor >= s.min && valor <= s.max) return s.color
        return segmentos.lastOrNull()?.color ?: Color.DKGRAY
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0 || segmentos.isEmpty()) return

        val pinH = 20f * d           // alto del globo del pin
        val pinGap = 4f * d          // separación globo-barra
        val barH = 10f * d
        val labelH = 14f * d
        val barTop = pinH + pinGap
        val barBottom = barTop + barH
        val padSide = 6f * d
        val barW = width - padSide * 2
        val rangoTotal = (maxTotal - minTotal).let { if (it < 0.0001) 1.0 else it }

        fun xDe(v: Double): Float =
            (padSide + barW * ((v - minTotal) / rangoTotal).coerceIn(0.0, 1.0)).toFloat()

        // Segmentos de la barra (con esquinas redondeadas en los extremos)
        val radius = barH / 2f
        segmentos.forEach { s ->
            barPaint.color = s.color
            barRect.set(xDe(s.min), barTop, xDe(s.max), barBottom)
            canvas.drawRect(barRect, barPaint)
        }
        // Redondear extremos tapando las esquinas exteriores con el color del primer/último segmento
        barPaint.color = segmentos.first().color
        canvas.drawCircle(xDe(minTotal) + radius, barTop + radius, radius, barPaint)
        barPaint.color = segmentos.last().color
        canvas.drawCircle(xDe(maxTotal) - radius, barTop + radius, radius, barPaint)

        // Etiquetas de cada segmento (centradas bajo su tramo)
        val labelY = barBottom + labelH
        segmentos.forEach { s ->
            val cx = (xDe(s.min) + xDe(s.max)) / 2f
            canvas.drawText(s.etiqueta, cx, labelY, labelPaint)
        }

        // Pin / globo con el valor
        if (valor > 0.0) {
            val px = xDe(valor)
            val numStr = if (valor == valor.toLong().toDouble()) valor.toLong().toString()
            else "%.1f".format(valor)
            val texto = if (sufijo.isNotBlank()) "$numStr$sufijo" else numStr
            val anchoTexto = pinTextPaint.measureText(texto)
            val pinW = max(28f * d, anchoTexto + 14f * d)
            val color = colorDelValor()

            pinPaint.color = color
            val left = (px - pinW / 2f).coerceIn(padSide, width - padSide - pinW)
            pinRect.set(left, 0f, left + pinW, pinH)
            canvas.drawRoundRect(pinRect, 5f * d, 5f * d, pinPaint)

            // Triángulo apuntando a la barra
            pinPath.reset()
            pinPath.moveTo(px - 5f * d, pinH)
            pinPath.lineTo(px + 5f * d, pinH)
            pinPath.lineTo(px, pinH + pinGap + 1f * d)
            pinPath.close()
            canvas.drawPath(pinPath, pinPaint)

            canvas.drawText(texto, pinRect.centerX(), pinH / 2f + 4f * d, pinTextPaint)
        }
    }
}
