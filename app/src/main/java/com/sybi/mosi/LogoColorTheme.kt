package com.sybi.mosi

import android.graphics.Bitmap
import android.graphics.Color
import androidx.palette.graphics.Palette
import kotlin.random.Random

/**
 * Genera los 5 colores de fondo del modo multicolor: uno de la gama de
 * verdes, uno de azules, uno de rojos, uno en escala de grises, y un
 * "comodín" de una gama intermedia (amarillo, naranja o violeta). El rango
 * de saturación/brillo va de tonos oscuros y mate hasta casi tan vivo como
 * el azul marino de la paleta predefinida (#0F3E82), para que haya variedad
 * y ese tono también sea alcanzable, manteniendo contraste con el logo.
 */
object LogoColorTheme {

    private const val SATURATION_MIN = 0.35f
    private const val SATURATION_MAX = 0.90f
    private const val VALUE_MIN = 0.16f
    private const val VALUE_MAX = 0.55f

    // El gris no tiene saturación que lo distinga, así que su rango de
    // brillo se mantiene aparte para no verse casi negro en el extremo bajo.
    private const val GRAY_VALUE_MIN = 0.25f
    private const val GRAY_VALUE_MAX = 0.55f

    private val HUE_VERDE = 85f..160f
    private val HUE_AZUL = 195f..255f
    private val HUE_ROJO = -15f..16f // envuelve alrededor de 0°

    private val HUES_COMODIN = listOf(
        45f..65f,   // amarillo
        18f..40f,   // naranja
        265f..292f, // violeta
    )

    // Desfases de tono (en grados) respecto al color del logo. NINGUNO es 0°, así que ningún
    // color de la paleta es el propio color del logo: los cuatro CONTRASTAN con él y entre sí,
    // rotados por la rueda de color. Un gris neutro cierra la paleta. Así un logo verde da
    // tonos distintos y contrastantes (y no un verde idéntico al logo como primer color).
    private val DESFASES_TONO = listOf(270f, 150f, 210f, 90f)

    /**
     * Genera la paleta de 5 colores a partir del logo cargado, tomándolo como SEMILLA: usa el
     * tono (hue) del color dominante del logo y genera cuatro colores que contrastan con él
     * rotando ese tono en la rueda de color, todos en el mismo rango mate (ni brillantes ni muy
     * saturados), más un gris neutro. Ninguno repite el color del logo. Si el logo no da un
     * color utilizable, cae en [generateRandomPalette].
     */
    fun generatePaletteFromBitmap(bitmap: Bitmap): List<Int> {
        val palette = runCatching {
            Palette.from(bitmap).maximumColorCount(24).generate()
        }.getOrNull() ?: return generateRandomPalette()

        val dominante = palette.dominantSwatch
            ?: palette.vibrantSwatch
            ?: palette.mutedSwatch
            ?: palette.darkVibrantSwatch
            ?: palette.lightVibrantSwatch
            ?: return generateRandomPalette()

        val hueBase = FloatArray(3).also { Color.colorToHSV(dominante.rgb, it) }[0]

        val colores = DESFASES_TONO.map { desfase ->
            val hue = (hueBase + desfase + 360f) % 360f
            val sat = randomInRange(SATURATION_MIN, SATURATION_MAX)
            val value = randomInRange(VALUE_MIN, VALUE_MAX)
            Color.HSVToColor(floatArrayOf(hue, sat, value))
        }.toMutableList()

        // Un gris neutro que siempre contrasta y descansa la vista.
        colores += randomGray()

        return colores.take(5)
    }

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
