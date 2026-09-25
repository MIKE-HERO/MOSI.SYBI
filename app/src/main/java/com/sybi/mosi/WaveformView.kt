package com.sybi.mosi

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View

class WaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val wavePaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 5f
        isAntiAlias = true
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val bgPaint = Paint().apply {
        color = Color.parseColor("#2B7DE9") // Azul similar a la foto
        style = Paint.Style.FILL
    }

    private val path = Path()

    // Guardamos los datos como enteros para no perder precisión
    private val points = mutableListOf<Int>()
    private val maxPoints = 300 // Número máximo de puntos en pantalla

    // Variables para autoescalar
    private var minValue = Int.MAX_VALUE
    private var maxValue = Int.MIN_VALUE

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Dibujar fondo azul
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        // Dibujar la onda
        if (points.size > 1) {
            path.reset()
            val stepX = width / (maxPoints - 1).toFloat()

            // Calcular el rango de datos para escalar verticalmente
            if (maxValue == Int.MIN_VALUE) return
            val range = (maxValue - minValue).toFloat().coerceAtLeast(1f) // Evitar división por cero
            val midY = height / 2f

            // Dibujar el primer punto
            val firstY = midY - ((points[0] - minValue) / range - 0.5f) * height
            path.moveTo(0f, firstY)

            for (i in 1 until points.size) {
                val x = i * stepX
                // Normalizar y escalar para que ocupe toda la altura
                val normalized = (points[i] - minValue) / range
                val y = midY - (normalized - 0.5f) * height

                path.lineTo(x, y)
            }
            canvas.drawPath(path, wavePaint)
        }
    }

    // Método para agregar un nuevo valor
    fun addPoint(value: Int) {
        points.add(value)

        // Actualizar mínimos y máximos automáticamente
        if (value < minValue) minValue = value
        if (value > maxValue) maxValue = value

        // Limitar la cantidad de puntos en pantalla
        if (points.size > maxPoints) {
            points.removeAt(0)
        }

        // Redibuja la vista
        invalidate()
    }

    fun clear() {
        points.clear()
        minValue = Int.MAX_VALUE
        maxValue = Int.MIN_VALUE
        invalidate()
    }
}