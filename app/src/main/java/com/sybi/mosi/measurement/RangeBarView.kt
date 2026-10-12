package com.sybi.mosi.measurement

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs
import kotlin.math.max

/**
 * Barra de rango horizontal con un pin flotante que marca el valor, como las del informe
 * MediTech (IMC, tasa de grasa, grasa visceral, temperatura, SpO2). La barra se divide en
 * segmentos de color; debajo van las etiquetas de cada segmento; arriba, un "globo" con el
 * valor apuntando a su posición. Dibujo directo en Canvas (equipo de 2GB).
 *
 * Modo "anchos iguales": cuando se activa con [setAnchosIguales], todos los segmentos se
 * dibujan con el mismo ancho sin importar su rango numérico. El pin del valor sigue
 * ubicándose proporcionalmente DENTRO del segmento que contiene al valor, no en la
 * posición absoluta del rango. Se usa en las barras con 4-5 segmentos donde las etiquetas
 * no caben (presión sistólica/diastólica, tasa de masa muscular).
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
    private var anchosIguales: Boolean = false

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

    /**
     * Si es true, todos los segmentos se dibujan con el mismo ancho, independientemente
     * de su rango numérico. El pin sigue ubicándose según el valor real dentro del
     * segmento que le corresponde.
     */
    fun setAnchosIguales(iguales: Boolean) {
        anchosIguales = iguales
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

        val pinH = 20f * d
        val pinGap = 4f * d
        val barH = 10f * d
        val labelH = 26f * d
        val barTop = pinH + pinGap
        val barBottom = barTop + barH
        val padSide = 6f * d
        val barW = width - padSide * 2
        val rangoTotal = (maxTotal - minTotal).let { if (it < 0.0001) 1.0 else it }

        // Posición proporcional real (para el pin cuando NO se usan anchos iguales)
        fun xReal(v: Double): Float =
            (padSide + barW * ((v - minTotal) / rangoTotal).coerceIn(0.0, 1.0)).toFloat()

        // Ancho uniforme por segmento cuando se usa el modo anchos iguales
        val n = segmentos.size
        val anchoSeg = if (anchosIguales && n > 0) barW / n else 0f

        fun xDeSegmento(index: Int, esFin: Boolean): Float {
            return if (anchosIguales) {
                val base = padSide + anchoSeg * index
                if (esFin) base + anchoSeg else base
            } else {
                val s = segmentos[index]
                if (esFin) xReal(s.max) else xReal(s.min)
            }
        }

        // ── Dibujo de segmentos ──
        val radius = barH / 2f
        segmentos.forEachIndexed { i, s ->
            barPaint.color = s.color
            val x0 = xDeSegmento(i, esFin = false)
            val x1 = xDeSegmento(i, esFin = true)
            barRect.set(x0, barTop, x1, barBottom)
            canvas.drawRect(barRect, barPaint)
        }

        // Redondear extremos con el color del primer/último segmento
        barPaint.color = segmentos.first().color
        canvas.drawCircle(padSide + radius, barTop + radius, radius, barPaint)
        barPaint.color = segmentos.last().color
        canvas.drawCircle(padSide + barW - radius, barTop + radius, radius, barPaint)

        // ── Etiquetas centradas bajo cada segmento ──
        val labelY = barBottom + labelH
        segmentos.forEachIndexed { i, s ->
            val x0 = xDeSegmento(i, esFin = false)
            val x1 = xDeSegmento(i, esFin = true)
            val cx = (x0 + x1) / 2f
            canvas.drawText(s.etiqueta, cx, labelY, labelPaint)
        }

        // ── Pin del valor ──
        if (valor > 0.0) {
            val px: Float = if (anchosIguales) {
                val idx = segmentos.indexOfFirst { valor >= it.min && valor <= it.max }
                    .takeIf { it >= 0 } ?: (segmentos.size - 1)
                val s = segmentos[idx]
                val rangoSeg = (s.max - s.min).let { if (it < 0.0001) 1.0 else it }
                val frac = ((valor - s.min) / rangoSeg).coerceIn(0.0, 1.0).toFloat()
                val x0 = xDeSegmento(idx, esFin = false)
                x0 + anchoSeg * frac
            } else {
                xReal(valor)
            }

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

            pinPath.reset()
            pinPath.moveTo(px - 5f * d, pinH)
            pinPath.lineTo(px + 5f * d, pinH)
            pinPath.lineTo(px, pinH + pinGap + 1f * d)
            pinPath.close()
            canvas.drawPath(pinPath, pinPaint)

            canvas.drawText(texto, pinRect.centerX(), pinH / 2f + 4f * d, pinTextPaint)
        }
    }

    /**
     * Divide un texto en dos líneas lo más equilibradas posible, cortando por el espacio
     * más cercano a la mitad. Si no hay espacios, corta por la mitad exacta.
     * (Disponible por si más adelante quieres usarlo; no se usa en el flujo actual de
     * anchos iguales.)
     */
    @Suppress("unused")
    private fun partirEnDosLineas(texto: String): Pair<String, String> {
        if (texto.length <= 3) return texto to ""
        val mitad = texto.length / 2
        var mejorIdx = -1
        var mejorDist = Int.MAX_VALUE
        texto.forEachIndexed { i, c ->
            if (c == ' ') {
                val dist = abs(i - mitad)
                if (dist < mejorDist) {
                    mejorDist = dist
                    mejorIdx = i
                }
            }
        }
        return if (mejorIdx > 0) {
            texto.substring(0, mejorIdx) to texto.substring(mejorIdx + 1)
        } else {
            texto.substring(0, mitad) to texto.substring(mitad)
        }
    }
}