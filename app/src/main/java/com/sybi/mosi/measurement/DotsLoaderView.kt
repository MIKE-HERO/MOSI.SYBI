package com.sybi.mosi.measurement

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.LinearLayout

/**
 * Loader de 3 puntos que se agrandan y achican en secuencia (estilo "escribiendo"), para
 * espacios más chicos que no alcanzan para el loader de barritas (como las píldoras
 * secundarias de Composición Corporal). Misma lógica que [EqualizerLoaderView]: anima con
 * ObjectAnimator (barato en CPU), toma el color de marca del quiosco, y pausa sola la
 * animación en cuanto deja de estar visible.
 */
class DotsLoaderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    private val puntos = mutableListOf<View>()
    private val animadores = mutableListOf<ObjectAnimator>()

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER
        val diametroPx = (6 * resources.displayMetrics.density).toInt()
        val espacioPx = (5 * resources.displayMetrics.density).toInt()

        repeat(3) { i ->
            val punto = View(context).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor("#0F3E82"))
                }
            }
            val lp = LayoutParams(diametroPx, diametroPx).apply {
                if (i > 0) marginStart = espacioPx
            }
            addView(punto, lp)
            puntos += punto
        }
    }

    /** Recolorea los puntos según el color de marca del quiosco. */
    fun setDotColor(color: Int) {
        puntos.forEach { (it.background as? GradientDrawable)?.setColor(color) }
    }

    private fun crearAnimadoresSiHacenFalta() {
        if (animadores.isNotEmpty()) return
        puntos.forEachIndexed { i, punto ->
            animadores += ObjectAnimator.ofPropertyValuesHolder(
                punto,
                android.animation.PropertyValuesHolder.ofFloat(View.SCALE_X, 0.4f, 1f),
                android.animation.PropertyValuesHolder.ofFloat(View.SCALE_Y, 0.4f, 1f)
            ).apply {
                duration = 400
                startDelay = i * 150L
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                interpolator = AccelerateDecelerateInterpolator()
            }
        }
    }

    private fun iniciarAnimacion() {
        crearAnimadoresSiHacenFalta()
        animadores.forEach { if (!it.isStarted) it.start() }
    }

    private fun detenerAnimacion() {
        animadores.forEach { it.cancel() }
        puntos.forEach { it.scaleX = 1f; it.scaleY = 1f }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (visibility == VISIBLE) iniciarAnimacion()
    }

    override fun onDetachedFromWindow() {
        detenerAnimacion()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE) iniciarAnimacion() else detenerAnimacion()
    }
}
