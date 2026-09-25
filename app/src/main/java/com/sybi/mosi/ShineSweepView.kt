package com.sybi.mosi

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.hypot

/**
 * Franja de brillo diagonal que recorre toda la pantalla cada cierto tiempo, para dar sensación
 * de vida a una pantalla de reposo (idle) sin distraer. Puramente decorativa: nunca intercepta
 * toques (isClickable=false), así que se puede superponer a cualquier pantalla sin romper nada.
 *
 * Uso: colocarla como el último hijo del layout (para quedar por encima visualmente) y llamar
 * startSweeping() / stopSweeping() según el ciclo de vida de la Activity (onResume/onPause).
 */
class ShineSweepView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        private const val SWEEP_DURATION_MS = 2200L
        private const val PAUSE_BETWEEN_MS = 6000L
        private const val INITIAL_DELAY_MS = 1200L
        private const val TILT_DEGREES = -18f
        private const val BAND_WIDTH_RATIO = 0.28f
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val matrix = Matrix()
    private var shader: LinearGradient? = null
    private var bandWidth = 0f
    private var translateX = 0f
    private var animator: ValueAnimator? = null
    private var running = false

    private val sweepRunnable = Runnable { runSweep() }

    init {
        isClickable = false
        isFocusable = false
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0) return
        bandWidth = w * BAND_WIDTH_RATIO
        // Franja translúcida: transparente -> brillo suave -> transparente
        shader = LinearGradient(
            0f, 0f, bandWidth, 0f,
            intArrayOf(0x00FFFFFF, 0x40FFFFFF, 0x00FFFFFF),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        paint.shader = shader
    }

    /** Empieza el ciclo de barridos (con una pausa inicial y otra entre cada barrido). */
    fun startSweeping() {
        if (running) return
        running = true
        postDelayed(sweepRunnable, INITIAL_DELAY_MS)
    }

    /** Detiene el barrido actual y cancela los pendientes. Llamar en onPause para no gastar batería. */
    fun stopSweeping() {
        running = false
        removeCallbacks(sweepRunnable)
        animator?.cancel()
        animator = null
    }

    private fun runSweep() {
        if (!running || width <= 0) return
        val start = -bandWidth
        val end = width.toFloat() + bandWidth
        animator = ValueAnimator.ofFloat(start, end).apply {
            duration = SWEEP_DURATION_MS
            interpolator = LinearInterpolator()
            addUpdateListener {
                translateX = it.animatedValue as Float
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (running) postDelayed(sweepRunnable, PAUSE_BETWEEN_MS)
                }
            })
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (shader == null || width <= 0 || height <= 0) return
        matrix.reset()
        matrix.postTranslate(translateX, 0f)
        shader!!.setLocalMatrix(matrix)
        // Se rota el lienzo para que la franja quede en diagonal; se dibuja más allá del borde
        // (la diagonal completa) para que la rotación no deje huecos en las esquinas.
        val diagonal = hypot(width.toDouble(), height.toDouble()).toFloat()
        canvas.save()
        canvas.rotate(TILT_DEGREES, width / 2f, height / 2f)
        canvas.drawRect(-diagonal, -diagonal, diagonal, diagonal, paint)
        canvas.restore()
    }
}
