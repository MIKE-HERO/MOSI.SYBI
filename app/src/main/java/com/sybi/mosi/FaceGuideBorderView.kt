package com.sybi.mosi

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/**
 * Borde de guía para el reconocimiento facial, dibujado directamente en Canvas en vez de con un
 * <shape> (GradientDrawable). El drawable con stroke + corners mostraba un artefacto visible en
 * las esquinas (una especie de muesca/doble línea) sin importar si el trazo era punteado o
 * sólido — es un problema de renderizado conocido de GradientDrawable con esquinas redondeadas,
 * no del patrón de línea. Dibujarlo a mano con Paint(ANTI_ALIAS_FLAG) evita ese artefacto.
 */
class FaceGuideBorderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val strokeWidthPx = resources.displayMetrics.density * 3f
    private val cornerRadiusPx = resources.displayMetrics.density * 20f

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = strokeWidthPx
        color = Color.parseColor("#4CAF50")
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val inset = strokeWidthPx / 2f
        canvas.drawRoundRect(
            inset,
            inset,
            width - inset,
            height - inset,
            cornerRadiusPx,
            cornerRadiusPx,
            paint
        )
    }
}
