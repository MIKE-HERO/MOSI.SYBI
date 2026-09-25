package com.sybi.mosi.measurement

import android.graphics.Bitmap

/**
 * Estado completo de la sesión de medición.
 * Reemplaza las decenas de variables sueltas en MeasurementActivity.
 */
data class MeasurementState(
    // Altura/Peso
    var height: Double = 0.0,
    var weight: Double = 0.0,
    var imc: Double = 0.0,

    // Composición
    var fatRate: Double = 0.0,
    var fatKg: Double = 0.0,
    var waterRate: Double = 0.0,
    var waterKg: Double = 0.0,
    var muscle: Double = 0.0,
    var notFat: Double = 0.0,
    var protein: Double = 0.0,
    var mineral: Double = 0.0,
    var metabolism: Int = 0,
    var visceralFat: Double = 0.0,
    var idealWeight: Double = 0.0,
    var fatType: Int = 0,

    // Temperatura
    var temperature: Double = 0.0,
    var temperatureF: Double = 0.0,
    var bodyMode: String = "",

    // Presión
    var systolic: Int = 0,
    var diastolic: Int = 0,
    var pulse: Int = 0,

    // Oxígeno
    var spo2: Int = 0,
    var pulseRate: Int = 0,
    var pi: Double = 0.0,

    // Oxígeno pendiente
    var pendingSpO2: Int = 0,
    var pendingPulseRate: Int = 0,
    var pendingPI: Double = 0.0,
    var hasPendingResult: Boolean = false,

    // ECG
    var ecgResults: EcgResults? = null,
    var ecgImage: Bitmap? = null
) {
    fun hasHeightWeight() = height > 0 && weight > 0
    fun hasComposition() = fatRate > 0 && waterRate > 0
    fun hasPressure() = systolic > 0 && diastolic > 0
    fun hasTemperature() = temperature > 0
    fun hasOxygen() = spo2 > 0
    fun hasEcg() = ecgResults != null

    fun resetHeightWeight() {
        height = 0.0; weight = 0.0; imc = 0.0
    }

    fun resetComposition() {
        fatRate = 0.0; fatKg = 0.0; waterRate = 0.0; waterKg = 0.0
        muscle = 0.0; notFat = 0.0; protein = 0.0; mineral = 0.0
        metabolism = 0; visceralFat = 0.0; idealWeight = 0.0; fatType = 0
    }

    fun resetTemperature() {
        temperature = 0.0; temperatureF = 0.0; bodyMode = ""
    }

    fun resetPressure() {
        systolic = 0; diastolic = 0; pulse = 0
    }

    fun resetOxygen() {
        spo2 = 0; pulseRate = 0; pi = 0.0
        pendingSpO2 = 0; pendingPulseRate = 0; pendingPI = 0.0
        hasPendingResult = false
    }

    fun resetEcg() {
        ecgResults = null
        ecgImage = null
    }
}