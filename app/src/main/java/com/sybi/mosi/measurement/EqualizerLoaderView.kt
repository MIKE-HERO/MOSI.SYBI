package com.sybi.mosi.measurement

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.LinearLayout

/**
 * Loader estilo ecualizador: barritas verticales que suben y bajan en cascada mientras
 * se espera un dato (altura, peso, presión, etc.).
 *
 * Pensado para un equipo con pocos recursos (2GB de RAM): solo anima `scaleY` con
 * ObjectAnimator (acelerado por GPU, sin redibujar nada a mano en cada frame) y cancela
 * las animaciones en cuanto la vista deja de estar visible, para no gastar CPU de fondo
 * en las tarjetas que ya muestran su valor real.
 */
class EqualizerLoaderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    private val barras = mutableListOf<View>()
    private val animadores = mutableListOf<ObjectAnimator>()

    init {
        orientation = HORIZONTAL
        gravity = Gravity.BOTTOM
        val anchoBarraPx = (4 * resources.displayMetrics.density).toInt()
        val espacioPx = (4 * resources.displayMetrics.density).toInt()
        val altoMaxPx = (28 * resources.displayMetrics.density).toInt()

        repeat(4) { i ->
            val barra = View(context).apply {
                setBackgroundColor(Color.parseColor("#0F3E82"))
                pivotY = altoMaxPx.toFloat()
            }
            val lp = LayoutParams(anchoBarraPx, altoMaxPx).apply {
                if (i > 0) marginStart = espacioPx
            }
            addView(barra, lp)
            barras += barra
        }
    }

    /** Recolorea las barras según el color de marca del quiosco (mismo que usan los demás loaders). */
    fun setBarColor(color: Int) {
        barras.forEach { it.setBackgroundColor(color) }
    }

    private fun crearAnimadoresSiHacenFalta() {
        if (animadores.isNotEmpty()) return
        barras.forEachIndexed { i, barra ->
            animadores += ObjectAnimator.ofFloat(barra, "scaleY", 0.25f, 1f).apply {
                duration = 450
                startDelay = i * 120L
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
        barras.forEach { it.scaleY = 1f }
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
