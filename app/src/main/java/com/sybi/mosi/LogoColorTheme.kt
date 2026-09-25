package com.sybi.mosi

import android.graphics.Color
import kotlin.random.Random

/**
 * Genera los 5 colores de fondo del modo multicolor: uno de la gama de
 * verdes, uno de azules, uno de rojos, uno en escala de grises, y un
 * "comodín" de una gama intermedia (amarillo, naranja o violeta). Todos en
 * tonos oscuros y mate para que el logo (colores claros) resalte por
 * contraste en vez de mezclarse con el fondo.
 */
object LogoColorTheme {

    // Rango oscuro y mate.
    private const val SATURATION_MIN = 0.38f
    private const val SATURATION_MAX = 0.55f
    private const val VALUE_MIN = 0.16f
    private const val VALUE_MAX = 0.28f

    // El gris no tiene saturación que lo distinga, así que necesita algo
    // más de brillo que los demás para no verse casi negro.
    private const val GRAY_VALUE_MIN = 0.32f
    private const val GRAY_VALUE_MAX = 0.42f

    private val HUE_VERDE = 95f..150f
    private val HUE_AZUL = 200f..250f
    private val HUE_ROJO = -12f..14f // envuelve alrededor de 0°

    private val HUES_COMODIN = listOf(
        45f..65f,   // amarillo
        18f..40f,   // naranja
        265f..292f, // violeta
    )

    fun generateRandomPalette(): List<Int> {
        return listOf(
            randomColorInHueRange(HUE_VERDE),
            randomColorInHueRange(HUE_AZUL),
            randomColorInHueRange(HUE_ROJO),
            randomGray(),
            randomColorInHueRange(HUES_COMODIN.random()),
        )
    }

    private fun randomColorInHueRange(range: ClosedFloatingPointRange<Float>): Int {
        val hue = (randomInRange(range.start, range.endInclusive) + 360f) % 360f
        val saturation = randomInRange(SATURATION_MIN, SATURATION_MAX)
        val value = randomInRange(VALUE_MIN, VALUE_MAX)
        return Color.HSVToColor(floatArrayOf(hue, saturation, value))
    }

    private fun randomGray(): Int {
        val value = randomInRange(GRAY_VALUE_MIN, GRAY_VALUE_MAX)
        return Color.HSVToColor(floatArrayOf(0f, 0f, value))
    }

    private fun randomInRange(min: Float, max: Float): Float {
        return Random.nextFloat() * (max - min) + min
    }
}
