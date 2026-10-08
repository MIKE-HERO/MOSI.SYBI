package com.sybi.mosi.measurement

import android.graphics.Color

/**
 * Umbrales/rangos de salud que ya usa la app para clasificar cada medición (los mismos que
 * MeasurementResults usa para colorear y etiquetar), expuestos como segmentos de barra para
 * el informe (RangeBarView). Así las barras del informe usan exactamente los parámetros que
 * la app ya tiene, en un solo lugar.
 *
 * Grasa corporal, masa muscular y agua corporal dependen del sexo y la edad; los demás son
 * fijos.
 */
object RangosSalud {

    private val AMARILLO = Color.parseColor("#E3B814")
    private val VERDE = Color.parseColor("#32A852")
    private val VERDE_OSC = Color.parseColor("#2E7D32")
    private val AZUL = Color.parseColor("#1E88E5")
    private val NARANJA = Color.parseColor("#E37214")
    private val ROJO = Color.parseColor("#D32F2F")

    private fun seg(etiqueta: String, min: Double, max: Double, color: Int) =
        RangeBarView.Segmento(etiqueta, min, max, color)

    // ── Fijos ────────────────────────────────────────────────────────────────

    fun imc() = listOf(
        seg("Bajo", 10.0, 18.5, AMARILLO),
        seg("Normal", 18.5, 24.9, VERDE),
        seg("Sobrepeso", 24.9, 29.9, NARANJA),
        seg("Obesidad", 29.9, 40.0, ROJO)
    )

    fun temperatura() = listOf(
        seg("Hipotermia", 32.0, 35.0, AZUL),
        seg("Normal", 35.0, 37.5, VERDE),
        seg("Fiebre leve", 37.5, 39.0, AMARILLO),
        seg("Fiebre alta", 39.0, 41.5, NARANJA),
        seg("Hipertermia", 41.5, 43.0, ROJO)
    )

    fun spo2() = listOf(
        seg("Baja", 70.0, 90.0, NARANJA),
        seg("Normal", 90.0, 95.0, VERDE),
        seg("Óptima", 95.0, 100.0, VERDE_OSC)
    )

    /** Presión sistólica (rangos alineados con getPressureClassification). */
    fun presionSistolica() = listOf(
        seg("Baja", 40.0, 90.0, AMARILLO),
        seg("Normal", 90.0, 120.0, VERDE),
        seg("Elevada", 120.0, 129.0, NARANJA),
        seg("Hipertensión 1", 129.0, 139.0, NARANJA),
        seg("Hipertensión 2", 139.0, 200.0, ROJO)
    )

    fun grasaVisceral() = listOf(
        seg("Normal", 0.0, 9.0, VERDE),
        seg("Alto", 9.0, 14.0, NARANJA),
        seg("Muy alto", 14.0, 30.0, ROJO)
    )

    // ── Dependientes de sexo y edad (mismos umbrales que MeasurementResults) ──

    fun grasaCorporal(isMale: Boolean, age: Int): List<RangeBarView.Segmento> {
        val (pBajo, pFitness, pAceptable) = if (isMale) when {
            age <= 19 -> Triple(5.0, 16.0, 23.0)
            age <= 39 -> Triple(5.0, 17.0, 24.0)
            age <= 59 -> Triple(5.0, 19.0, 26.0)
            else -> Triple(5.0, 21.0, 29.0)
        } else when {
            age <= 19 -> Triple(13.0, 22.0, 29.0)
            age <= 39 -> Triple(13.0, 24.0, 32.0)
            age <= 59 -> Triple(13.0, 26.0, 34.0)
            else -> Triple(13.0, 27.0, 36.0)
        }
        return listOf(
            seg("Bajo", 0.0, pBajo, AMARILLO),
            seg("Óptimo", pBajo, pFitness, VERDE),
            seg("Aceptable", pFitness, pAceptable, AZUL),
            seg("Exceso", pAceptable, 50.0, ROJO)
        )
    }

    fun masaMuscular(isMale: Boolean, age: Int): List<RangeBarView.Segmento> {
        val (pInsuf, pAcept, pSalud) = if (isMale) when {
            age <= 19 -> Triple(65.0, 69.0, 76.0)
            age <= 39 -> Triple(68.0, 71.0, 79.0)
            age <= 59 -> Triple(64.0, 68.0, 76.0)
            else -> Triple(60.0, 64.0, 72.0)
        } else when {
            age <= 19 -> Triple(58.0, 61.0, 68.0)
            age <= 39 -> Triple(60.0, 64.0, 72.0)
            age <= 59 -> Triple(58.0, 60.0, 69.0)
            else -> Triple(52.0, 56.0, 65.0)
        }
        return listOf(
            seg("Insuficiente", 30.0, pInsuf, AMARILLO),
            seg("Aceptable", pInsuf, pAcept, AZUL),
            seg("Saludable", pAcept, pSalud, VERDE),
            seg("Atlético", pSalud, 95.0, VERDE_OSC)
        )
    }

    fun aguaCorporal(isMale: Boolean, age: Int): List<RangeBarView.Segmento> {
        val (pDeshid, pBaja, pOptima) = if (isMale) when {
            age <= 19 -> Triple(50.0, 55.0, 63.0)
            age <= 50 -> Triple(45.0, 53.0, 65.0)
            else -> Triple(40.0, 49.0, 60.0)
        } else when {
            age <= 19 -> Triple(45.0, 50.0, 58.0)
            age <= 50 -> Triple(40.0, 46.0, 57.0)
            else -> Triple(38.0, 42.0, 52.0)
        }
        return listOf(
            seg("Deshidratación", 25.0, pDeshid, ROJO),
            seg("Baja", pDeshid, pBaja, NARANJA),
            seg("Óptima", pBaja, pOptima, VERDE),
            seg("Alta", pOptima, 80.0, AZUL)
        )
    }
}
