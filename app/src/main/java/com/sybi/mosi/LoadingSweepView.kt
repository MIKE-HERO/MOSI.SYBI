package com.sybi.mosi

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator

/**
 * Barrido de color continuo (tipo "loading shimmer") sobre una píldora, para indicar que se está
 * esperando un resultado. A diferencia de ShineSweepView (decorativo, con pausas), este corre en
 * bucle continuo mientras start() esté activo, y respeta las esquinas redondeadas del contenedor.
 */
class LoadingSweepView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        private const val SWEEP_DURATION_MS = 1100L
        private const val BAND_WIDTH_RATIO = 0.55f
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val matrix = Matrix()
    private var cornerRadius = 0f
    private var bandWidth = 0f
    private var translateX = 0f
    private var animator: ValueAnimator? = null
    private var sweepColors = intArrayOf(0x00FFFFFF, 0x55FFFFFF, 0x00FFFFFF)
    private var pendingStart = false

    init {
        isClickable = false
        isFocusable = false
    }

    /** Fija el color del barrido (se vuelve translúcido automáticamente). */
    fun setSweepColor(color: Int) {
        val opaque = color and 0x00FFFFFF
        sweepColors = intArrayOf(opaque, (0x66 shl 24) or opaque, opaque)
        buildShader()
        invalidate()
    }

    fun setCornerRadiusDp(radiusDp: Float) {
        cornerRadius = radiusDp * resources.displayMetrics.density
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0) return
        bandWidth = w * BAND_WIDTH_RATIO
        buildShader()
        if (pendingStart) {
            pendingStart = false
            startInternal()
        }
    }

    private fun buildShader() {
        if (bandWidth <= 0f) return
        val shader = LinearGradient(
            0f, 0f, bandWidth, 0f,
            sweepColors,
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        paint.shader = shader
    }

    /** Arranca el barrido. Si la vista aún no tiene tamaño (p. ej. primer frame tras onCreate),
     *  queda pendiente y arranca sola en cuanto se mida (ver onSizeChanged). */
    fun start() {
        if (animator != null) return
        if (width <= 0) {
            pendingStart = true
            return
        }
        startInternal()
    }

    private fun startInternal() {
        if (animator != null || width <= 0) return
        val startX = -bandWidth
        val endX = width.toFloat() + bandWidth
        animator = ValueAnimator.ofFloat(startX, endX).apply {
            duration = SWEEP_DURATION_MS
            interpolator = LinearInterpolator()
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener {
                translateX = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    fun stop() {
        pendingStart = false
        animator?.cancel()
        animator = null
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (paint.shader == null || width <= 0 || height <= 0) return
        matrix.reset()
        matrix.postTranslate(translateX, 0f)
        paint.shader?.setLocalMatrix(matrix)
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), cornerRadius, cornerRadius, paint)
    }
}
