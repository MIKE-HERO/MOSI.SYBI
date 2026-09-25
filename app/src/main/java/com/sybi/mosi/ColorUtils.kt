package com.sybi.mosi

import android.graphics.Color

object ColorUtils {
    // Mezcla el color original con un porcentaje de blanco
    fun getTintedColor(originalColor: Int, whiteAmount: Float): Int {
        // Obtener componentes RGB del color original
        val r = Color.red(originalColor)
        val g = Color.green(originalColor)
        val b = Color.blue(originalColor)

        // Mezclar con blanco (255, 255, 255) según el porcentaje
        val newR = (r + (255 - r) * whiteAmount).toInt()
        val newG = (g + (255 - g) * whiteAmount).toInt()
        val newB = (b + (255 - b) * whiteAmount).toInt()

        return Color.rgb(newR, newG, newB)
    }
}