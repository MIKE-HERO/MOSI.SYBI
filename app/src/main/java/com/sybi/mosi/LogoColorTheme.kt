package com.sybi.mosi

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import androidx.palette.graphics.Palette
import coil.ImageLoader
import coil.decode.SvgDecoder
import coil.request.ImageRequest
import coil.request.SuccessResult
import java.io.File
import kotlin.math.abs

/**
 * Genera 5 colores de fondo "que combinan" con el logo actual, extrayendo sus
 * tonos dominantes (Palette) y separándolos en el círculo de matices para que
 * no queden colores demasiado parecidos entre sí.
 */
object LogoColorTheme {

    const val COLOR_COUNT = 5
    private const val MIN_HUE_SEPARATION = 20f

    // Mismo peso tonal que el navy por defecto (#0F3E82 ≈ H215 S0.885 V0.51)
    private const val SATURATION = 0.82f
    private const val VALUE = 0.46f

    suspend fun generateFromLogo(context: Context, logoPath: String?, fallbackColor: Int): List<Int> {
        val seedHues = extractHuesFromLogo(context, logoPath)
        val baseHue = seedHues.firstOrNull() ?: run {
            val hsv = FloatArray(3)
            Color.colorToHSV(fallbackColor, hsv)
            hsv[0]
        }
        val hues = buildFiveHues(seedHues, baseHue)
        return hues.map { hue -> Color.HSVToColor(floatArrayOf(hue, SATURATION, VALUE)) }
    }

    private suspend fun extractHuesFromLogo(context: Context, logoPath: String?): List<Float> {
        if (logoPath.isNullOrBlank()) return emptyList()
        return try {
            val imageLoader = ImageLoader.Builder(context)
                .components { add(SvgDecoder.Factory()) }
                .allowHardware(false)
                .build()

            val file = File(logoPath)
            val data: Any = if (file.exists()) file else Uri.parse(logoPath)

            val request = ImageRequest.Builder(context)
                .data(data)
                .allowHardware(false)
                .build()

            val result = imageLoader.execute(request)
            val drawable = (result as? SuccessResult)?.drawable ?: return emptyList()

            val width = drawable.intrinsicWidth.takeIf { it > 0 } ?: 200
            val height = drawable.intrinsicHeight.takeIf { it > 0 } ?: 200
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            drawable.setBounds(0, 0, width, height)
            drawable.draw(canvas)

            Palette.from(bitmap).maximumColorCount(24).generate()
                .swatches
                .sortedByDescending { it.population }
                .mapNotNull { swatch ->
                    val hsv = FloatArray(3)
                    Color.colorToHSV(swatch.rgb, hsv)
                    val saturation = hsv[1]
                    val value = hsv[2]
                    val isNearGrayOrWhiteOrBlack = saturation < 0.15f || value < 0.12f
                    if (isNearGrayOrWhiteOrBlack) null else hsv[0]
                }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun buildFiveHues(seedHues: List<Float>, baseHue: Float): List<Float> {
        val result = mutableListOf<Float>()

        fun isFarEnough(hue: Float): Boolean {
            return result.all { existing ->
                val diff = abs(existing - hue) % 360f
                val angularDiff = if (diff > 180f) 360f - diff else diff
                angularDiff >= MIN_HUE_SEPARATION
            }
        }

        for (hue in seedHues) {
            if (result.size >= COLOR_COUNT) break
            if (isFarEnough(hue)) result.add(hue)
        }

        // Rellenar los huecos con tonos análogos alrededor del matiz principal,
        // alternando +/- y ampliando el paso hasta lograr separación suficiente.
        var step = 40f
        var sign = 1f
        var attempts = 0
        while (result.size < COLOR_COUNT && attempts < 40) {
            val candidate = (baseHue + sign * step + 360f) % 360f
            if (isFarEnough(candidate)) {
                result.add(candidate)
            }
            if (sign < 0) step += 40f
            sign = -sign
            attempts++
        }

        return result.take(COLOR_COUNT)
    }
}
