package com.sybi.mosi

import android.graphics.Bitmap
import android.graphics.Color
import androidx.palette.graphics.Palette
import kotlin.math.abs
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

    /**
     * Genera la paleta de 5 colores a partir del logo cargado: toma las muestras que ya
     * clasifica [Palette] (oscura/mate, vibrante oscura, mate, dominante, clara/mate...),
     * atenúa las que resulten muy brillantes o muy saturadas, y descarta las que queden
     * demasiado parecidas entre sí (para que al ciclar sí se note el cambio). Si el logo no
     * da suficientes tonos distintos, completa el resto con [generateRandomPalette].
     */
    fun generatePaletteFromBitmap(bitmap: Bitmap): List<Int> {
        val palette = runCatching {
            Palette.from(bitmap).maximumColorCount(24).generate()
        }.getOrNull() ?: return generateRandomPalette()

        // De más útil (tonos oscuros/mate, fáciles de usar de fondo) a menos útil.
        val candidatos = listOfNotNull(
            palette.darkMutedSwatch,
            palette.darkVibrantSwatch,
            palette.mutedSwatch,
            palette.dominantSwatch,
            palette.lightMutedSwatch,
            palette.vibrantSwatch,
            palette.lightVibrantSwatch,
        )

        val elegidos = mutableListOf<Int>()
        for (swatch in candidatos) {
            if (elegidos.size >= 5) break
            val color = atenuarSiEsMuyBrillante(swatch.rgb)
            if (elegidos.none { seVeParecido(it, color) }) elegidos += color
        }

        if (elegidos.size < 5) {
            for (extra in generateRandomPalette()) {
                if (elegidos.size >= 5) break
                if (elegidos.none { seVeParecido(it, extra) }) elegidos += extra
            }
        }

        return elegidos.take(5)
    }

    /** Si el color es muy brillante o muy saturado, lo baja para que quede dentro del
     *  mismo rango "mate" que usa [generateRandomPalette]. */
    private fun atenuarSiEsMuyBrillante(color: Int): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        if (hsv[2] > VALUE_MAX) hsv[2] = VALUE_MAX
        if (hsv[1] > SATURATION_MAX) hsv[1] = SATURATION_MAX
        return Color.HSVToColor(hsv)
    }

    /** Dos colores "se ven parecidos" si su tono y su brillo están ambos muy cerca. */
    private fun seVeParecido(a: Int, b: Int): Boolean {
        val hsvA = FloatArray(3).also { Color.colorToHSV(a, it) }
        val hsvB = FloatArray(3).also { Color.colorToHSV(b, it) }
        val difHue = abs(hsvA[0] - hsvB[0]).let { if (it > 180f) 360f - it else it }
        val difValue = abs(hsvA[2] - hsvB[2])
        return difHue < 25f && difValue < 0.15f
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
