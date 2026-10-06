package com.sybi.mosi.measurement

import android.app.Activity
import android.graphics.Color
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import com.sybi.mosi.R

/**
 * Clase encuadradora para administrar todas las vistas de resultados estilizadas
 * en la pantalla de mediciones.
 */
class MeasurementResults(private val activity: Activity) {

    data class RangeResult(
        val label: String,
        val iconResId: Int,
        val color: Int
    )

    // Altura / Peso / IMC
    var tvResultHeightValue: TextView? = null
    var tvResultWeightValue: TextView? = null
    var tvResultIMCValue: TextView? = null
    var tvImcStatus: TextView? = null
    var gaugeIMC: GaugeView? = null
    var ivHeightIcon: ImageView? = null
    var ivWeightIcon: ImageView? = null

    // Composición Corporal
    var tvComplexionTitle: TextView? = null
    var tvComplexionSub: TextView? = null
    var tvResultFatRate: TextView? = null
    var tvResultFatKg: TextView? = null
    var tvResultMuscleRate: TextView? = null
    var tvResultMuscleKg: TextView? = null
    var tvResultWaterRate: TextView? = null
    var tvResultWaterKg: TextView? = null
    var tvResultVisceralFat: TextView? = null
    var tvPillProtein: TextView? = null
    var tvPillMineral: TextView? = null
    var tvPillMetabolism: TextView? = null
    var tvPillNotFat: TextView? = null

    var ivFatIcon: ImageView? = null
    var ivMuscleIcon: ImageView? = null
    var ivWaterIcon: ImageView? = null
    var ivVisceralIcon: ImageView? = null

    // Temperatura
    var layoutResultTempContent: View? = null
    var ivTempIcon: ImageView? = null
    var tvResultTempMain: TextView? = null
    var tvResultTempSub: TextView? = null
    var tvTempStatus: TextView? = null

    // Oxígeno
    var ringSpO2: RingProgressView? = null
    var ringHeartRate: RingProgressView? = null
    var ringPI: RingProgressView? = null
    var ivSpO2RingIcon: ImageView? = null
    var ivHeartRateRingIcon: ImageView? = null
    var ivPIRingIcon: ImageView? = null

    // Presión
    var tvResultSystolicValue: TextView? = null
    var tvResultDiastolicValue: TextView? = null
    var tvPresionStatus: TextView? = null
    var ringPresionPulse: RingProgressView? = null
    var ivSystolicIcon: ImageView? = null
    var ivDiastolicIcon: ImageView? = null
    var ivPresionPulseRingIcon: ImageView? = null

    fun bindViews(root: View = activity.window.decorView) {
        // Altura / Peso / IMC
        tvResultHeightValue = root.findViewById(R.id.tvResultHeightValue)
        tvResultWeightValue = root.findViewById(R.id.tvResultWeightValue)
        tvResultIMCValue = root.findViewById(R.id.tvResultIMCValue)
        tvImcStatus = root.findViewById(R.id.tvImcStatus)
        gaugeIMC = root.findViewById(R.id.gaugeIMC)
        ivHeightIcon = root.findViewById(R.id.ivHeightIcon)
        ivWeightIcon = root.findViewById(R.id.ivWeightIcon)

        gaugeIMC?.setGaugeType(GaugeView.GaugeType.IMC)

        // Composición
        tvComplexionTitle = root.findViewById(R.id.tvComplexionTitle)
        tvComplexionSub = root.findViewById(R.id.tvComplexionSub)
        tvResultFatRate = root.findViewById(R.id.tvResultFatRate)
        tvResultFatKg = root.findViewById(R.id.tvResultFatKg)
        tvResultMuscleRate = root.findViewById(R.id.tvResultMuscleRate)
        tvResultMuscleKg = root.findViewById(R.id.tvResultMuscleKg)
        tvResultWaterRate = root.findViewById(R.id.tvResultWaterRate)
        tvResultWaterKg = root.findViewById(R.id.tvResultWaterKg)
        tvResultVisceralFat = root.findViewById(R.id.tvResultVisceralFat)
        tvPillProtein = root.findViewById(R.id.tvPillProtein)
        tvPillMineral = root.findViewById(R.id.tvPillMineral)
        tvPillMetabolism = root.findViewById(R.id.tvPillMetabolism)
        tvPillNotFat = root.findViewById(R.id.tvPillNotFat)

        ivFatIcon = root.findViewById(R.id.ivFatIcon)
        ivMuscleIcon = root.findViewById(R.id.ivMuscleIcon)
        ivWaterIcon = root.findViewById(R.id.ivWaterIcon)
        ivVisceralIcon = root.findViewById(R.id.ivVisceralIcon)

        // Temperatura
        layoutResultTempContent = root.findViewById(R.id.layoutResultTempContent)
        ivTempIcon = root.findViewById(R.id.ivTempIcon)
        tvResultTempMain = root.findViewById(R.id.tvResultTempMain)
        tvResultTempSub = root.findViewById(R.id.tvResultTempSub)
        tvTempStatus = root.findViewById(R.id.tvTempStatus)

        // Oxígeno
        ringSpO2 = root.findViewById(R.id.ringSpO2)
        ringHeartRate = root.findViewById(R.id.ringHeartRate)
        ringPI = root.findViewById(R.id.ringPI)
        ivSpO2RingIcon = root.findViewById(R.id.ivSpO2RingIcon)
        ivHeartRateRingIcon = root.findViewById(R.id.ivHeartRateRingIcon)
        ivPIRingIcon = root.findViewById(R.id.ivPIRingIcon)

        ringSpO2?.setColors(
            trackClr = Color.parseColor("#BBDEFB"),
            activeClr = Color.parseColor("#1E88E5"),
            textClr = Color.parseColor("#1E88E5")
        )
        ringHeartRate?.setColors(
            trackClr = Color.parseColor("#FFCDD2"),
            activeClr = Color.parseColor("#E53935"),
            textClr = Color.parseColor("#E53935")
        )
        ringPI?.setColors(
            trackClr = Color.parseColor("#C8E6C9"),
            activeClr = Color.parseColor("#388E3C"),
            textClr = Color.parseColor("#2E7D32")
        )

        // Presión
        tvResultSystolicValue = root.findViewById(R.id.tvResultSystolicValue)
        tvResultDiastolicValue = root.findViewById(R.id.tvResultDiastolicValue)
        tvPresionStatus = root.findViewById(R.id.tvPresionStatus)
        ringPresionPulse = root.findViewById(R.id.ringPresionPulse)
        ivSystolicIcon = root.findViewById(R.id.ivSystolicIcon)
        ivDiastolicIcon = root.findViewById(R.id.ivDiastolicIcon)
        ivPresionPulseRingIcon = root.findViewById(R.id.ivPresionPulseRingIcon)

        ringPresionPulse?.setColors(
            trackClr = Color.parseColor("#FFCDD2"),
            activeClr = Color.parseColor("#E53935"),
            textClr = Color.parseColor("#E53935")
        )
    }

    fun updateHeightWeight(height: Double, weight: Double, imc: Double) {
        if (height > 0 && weight > 0) {
            tvResultHeightValue?.text = "%.1f".format(height)
            tvResultHeightValue?.visibility = View.VISIBLE
            ivHeightIcon?.visibility = View.VISIBLE

            tvResultWeightValue?.text = "%.1f".format(weight)
            tvResultWeightValue?.visibility = View.VISIBLE
            ivWeightIcon?.visibility = View.VISIBLE

            tvResultIMCValue?.text = "%.1f".format(imc)
            tvResultIMCValue?.visibility = View.VISIBLE
            gaugeIMC?.setValue(imc, animated = true)
            gaugeIMC?.visibility = View.VISIBLE

            tvImcStatus?.visibility = View.GONE
        } else {
            tvResultHeightValue?.visibility = View.GONE
            ivHeightIcon?.visibility = View.GONE

            tvResultWeightValue?.visibility = View.GONE
            ivWeightIcon?.visibility = View.GONE

            tvResultIMCValue?.visibility = View.GONE
            gaugeIMC?.visibility = View.GONE
            tvImcStatus?.visibility = View.GONE
        }
    }

    fun clearComposition() {
        tvComplexionTitle?.text = "COMPLEXIÓN: EN ANÁLISIS"
        tvComplexionSub?.text = "Esperando resultados de composición..."

        ivFatIcon?.visibility = View.GONE
        tvResultFatRate?.visibility = View.GONE
        tvResultFatKg?.visibility = View.GONE

        ivMuscleIcon?.visibility = View.GONE
        tvResultMuscleRate?.visibility = View.GONE
        tvResultMuscleKg?.visibility = View.GONE

        ivWaterIcon?.visibility = View.GONE
        tvResultWaterRate?.visibility = View.GONE
        tvResultWaterKg?.visibility = View.GONE

        ivVisceralIcon?.visibility = View.GONE
        tvResultVisceralFat?.visibility = View.GONE

        tvPillProtein?.text = "Masa Protéica"
        tvPillMineral?.text = "Masa Mineral"
        tvPillMetabolism?.text = "Metabolísmo basal"
        tvPillNotFat?.text = "Masa libre de grasa"
    }

    fun updateComposition(state: MeasurementState, isMale: Boolean, age: Int) {
        if (!state.hasComposition()) {
            clearComposition()
            return
        }

        // 1. Complexión según FatType
        val (complexionTitle, complexionSub) = getFatTypeInfo(state.fatType)
        tvComplexionTitle?.text = complexionTitle
        tvComplexionSub?.text = complexionSub

        // 2. Grasa Corporal
        tvResultFatRate?.text = "%.1f %%".format(state.fatRate)
        tvResultFatRate?.visibility = View.VISIBLE
        tvResultFatKg?.text = "%.1f Kg".format(state.fatKg)
        tvResultFatKg?.visibility = View.VISIBLE
        val fatResult = evaluateFat(state.fatRate, isMale, age)
        ivFatIcon?.setImageResource(fatResult.iconResId)
        ivFatIcon?.visibility = View.VISIBLE

        // 3. Masa Muscular Total
        val muscleKg = state.muscle
        val muscleRate = if (state.weight > 0 && muscleKg > 0) (muscleKg / state.weight) * 100.0 else 0.0

        tvResultMuscleKg?.text = "%.1f Kg".format(muscleKg)
        tvResultMuscleKg?.visibility = View.VISIBLE
        tvResultMuscleRate?.text = "%.1f %%".format(muscleRate)
        tvResultMuscleRate?.visibility = View.VISIBLE

        val muscleResult = evaluateMuscle(muscleRate, isMale, age)
        ivMuscleIcon?.setImageResource(muscleResult.iconResId)
        ivMuscleIcon?.visibility = View.VISIBLE

        // 4. Agua Corporal
        tvResultWaterRate?.text = "%.0f%%".format(state.waterRate)
        tvResultWaterRate?.visibility = View.VISIBLE
        tvResultWaterKg?.text = "%.1f Kg".format(state.waterKg)
        tvResultWaterKg?.visibility = View.VISIBLE
        val waterResult = evaluateWater(state.waterRate, isMale, age)
        ivWaterIcon?.setImageResource(waterResult.iconResId)
        ivWaterIcon?.visibility = View.VISIBLE

        // 5. Grasa Visceral
        tvResultVisceralFat?.text = "%.1f".format(state.visceralFat)
        tvResultVisceralFat?.visibility = View.VISIBLE
        val visceralResult = evaluateVisceral(state.visceralFat)
        ivVisceralIcon?.setImageResource(visceralResult.iconResId)
        ivVisceralIcon?.visibility = View.VISIBLE

        // Píldoras / Tarjetas secundarias
        tvPillProtein?.text = "Masa Protéica\n%.1f kg - 15 %%".format(state.protein)
        tvPillMineral?.text = "Masa Mineral\n%.1f kg - 15 %%".format(state.mineral)
        tvPillMetabolism?.text = "Metabolísmo basal\n${state.metabolism} Kcal"
        tvPillNotFat?.text = "Masa libre de grasa\n%.1f kg".format(state.notFat)
    }

    fun updateTemperature(tempC: Double, tempF: Double) {
        if (tempC > 0) {
            tvResultTempMain?.text = "%.1f °C".format(tempC)
            val subF = if (tempF > 0) tempF else (tempC * 9 / 5 + 32)
            tvResultTempSub?.text = "%.2f °F".format(subF)

            layoutResultTempContent?.visibility = View.VISIBLE
        } else {
            layoutResultTempContent?.visibility = View.GONE
            tvTempStatus?.visibility = View.GONE
        }
    }

    fun updateOxygen(spo2: Int, pulseRate: Int, pi: Double) {
        if (spo2 > 0) {
            val spo2Ratio = (spo2 / 100f).coerceIn(0f, 1f)
            ringSpO2?.setData("SpO2", "$spo2", "%", spo2Ratio, animated = true)
            ringSpO2?.visibility = View.VISIBLE
            ivSpO2RingIcon?.visibility = View.VISIBLE

            val pulseRatio = if (pulseRate > 0) (pulseRate / 150f).coerceIn(0f, 1f) else 0f
            val pulseValStr = if (pulseRate > 0) "$pulseRate" else "--"
            ringHeartRate?.setData("BPM", pulseValStr, "", pulseRatio, animated = true)
            ringHeartRate?.visibility = View.VISIBLE
            ivHeartRateRingIcon?.visibility = View.VISIBLE

            val piRatio = if (pi > 0) (pi / 20.0).toFloat().coerceIn(0f, 1f) else 0f
            val piValStr = if (pi > 0) "%.1f".format(pi) else "--"
            ringPI?.setData("PI %", piValStr, "", piRatio, animated = true)
            ringPI?.visibility = View.VISIBLE
            ivPIRingIcon?.visibility = View.VISIBLE
        } else {
            ringSpO2?.setData("SpO2", "", "%", 0f, animated = false)
            ringSpO2?.visibility = View.GONE
            ivSpO2RingIcon?.visibility = View.GONE

            ringHeartRate?.setData("BPM", "", "", 0f, animated = false)
            ringHeartRate?.visibility = View.GONE
            ivHeartRateRingIcon?.visibility = View.GONE

            ringPI?.setData("PI %", "", "", 0f, animated = false)
            ringPI?.visibility = View.GONE
            ivPIRingIcon?.visibility = View.GONE
        }
    }

    fun updatePressure(systolic: Int, diastolic: Int, pulse: Int) {
        if (systolic > 0 && diastolic > 0) {
            tvResultSystolicValue?.text = "$systolic"
            tvResultSystolicValue?.visibility = View.VISIBLE
            ivSystolicIcon?.visibility = View.VISIBLE

            tvResultDiastolicValue?.text = "$diastolic"
            tvResultDiastolicValue?.visibility = View.VISIBLE
            ivDiastolicIcon?.visibility = View.VISIBLE

            val pulseRatio = if (pulse > 0) (pulse / 150f).coerceIn(0f, 1f) else 0f
            val pulseValStr = if (pulse > 0) "$pulse" else "--"
            ringPresionPulse?.setData("BPM", pulseValStr, "", pulseRatio, animated = true)
            ringPresionPulse?.visibility = View.VISIBLE
            ivPresionPulseRingIcon?.visibility = View.VISIBLE
        } else {
            tvResultSystolicValue?.visibility = View.GONE
            ivSystolicIcon?.visibility = View.GONE

            tvResultDiastolicValue?.visibility = View.GONE
            ivDiastolicIcon?.visibility = View.GONE

            ringPresionPulse?.setData("BPM", "", "", 0f, animated = false)
            ringPresionPulse?.visibility = View.GONE
            ivPresionPulseRingIcon?.visibility = View.GONE
            tvPresionStatus?.visibility = View.GONE
        }
    }

    // ── Clasificaciones de Salud ───────────────────────────────────

    private fun getImcClassification(imc: Double): Pair<String, Int> {
        return when {
            imc < 18.5 -> Pair("BAJO PESO", Color.parseColor("#E3B814"))
            imc <= 24.9 -> Pair("PESO NORMAL", Color.parseColor("#32A852"))
            imc <= 29.9 -> Pair("SOBREPESO", Color.parseColor("#E37214"))
            else -> Pair("OBESIDAD", Color.parseColor("#D93636"))
        }
    }

    private fun getTempClassification(tempC: Double): Pair<String, Int> {
        return when {
            tempC < 35.0 -> Pair("HIPOTERMIA", Color.parseColor("#1E88E5"))
            tempC <= 37.5 -> Pair("TEMPERATURA NORMAL", Color.parseColor("#32A852"))
            tempC <= 39.0 -> Pair("FIEBRE LEVE", Color.parseColor("#FBC02D"))
            tempC <= 41.5 -> Pair("FIEBRE ALTA", Color.parseColor("#E37214"))
            else -> Pair("HIPERTERMIA", Color.parseColor("#D32F2F"))
        }
    }

    private fun getSpo2Classification(spo2: Int): Pair<String, Int> {
        return when {
            spo2 >= 95 -> Pair("SATURACIÓN OPTIMA", Color.parseColor("#32A852"))
            spo2 >= 90 -> Pair("SATURACIÓN NORMAL", Color.parseColor("#32A852"))
            else -> Pair("SATURACIÓN BAJA", Color.parseColor("#E37214"))
        }
    }

    private fun getPressureClassification(sys: Int, dia: Int): Pair<String, Int> {
        return when {
            sys < 90 || dia < 60 -> Pair("PRESIÓN BAJA", Color.parseColor("#E3B814"))
            sys <= 120 && dia <= 80 -> Pair("PRESIÓN ARTERIAL NORMAL", Color.parseColor("#32A852"))
            sys <= 129 && dia <= 80 -> Pair("PRESIÓN ELEVADA", Color.parseColor("#E37214"))
            sys <= 139 || dia <= 89 -> Pair("HIPERTENSIÓN ETAPA 1", Color.parseColor("#E37214"))
            else -> Pair("HIPERTENSIÓN ETAPA 2", Color.parseColor("#D32F2F"))
        }
    }

    // ── Tablas de Composición Corporal ──────────────────────────────

    private fun getFatTypeInfo(fatType: Int): Pair<String, String> {
        return when (fatType) {
            1 -> Pair("COMPLEXIÓN: TIPO 1 - OBESIDAD OCULTA", "Porcentaje de grasa elevado combinado con una masa muscular deficiente.")
            2 -> Pair("COMPLEXIÓN: TIPO 2 - OBESO", "Niveles altos tanto de grasa como de peso corporal general.")
            3 -> Pair("COMPLEXIÓN: TIPO 3 - COMPLEXIÓN ROBUSTA", "Masa muscular desarrollada pero oculta bajo grasa corporal alta.")
            4 -> Pair("COMPLEXIÓN: TIPO 4 - FALTO DE EJERCICIO", "Grasa en niveles promedio, pero con masa muscular baja.")
            5 -> Pair("COMPLEXIÓN: TIPO 5 - ESTÁNDAR", "Equilibrio ideal y saludable entre masa muscular y tejido graso.")
            6 -> Pair("COMPLEXIÓN: TIPO 6 - MUSCULOSO ESTÁNDAR", "Nivel óptimo de grasa con masa muscular superior al promedio.")
            7 -> Pair("COMPLEXIÓN: TIPO 7 - DELGADO", "Masa grasa muy baja acompañada de una estructura muscular ligera.")
            8 -> Pair("COMPLEXIÓN: TIPO 8 - DELGADO Y MUSCULADO", "Grasa corporal muy baja con masa muscular desarrollada.")
            9 -> Pair("COMPLEXIÓN: TIPO 9 - MUY MUSCULADO", "Perfil atlético de élite con músculo masivo y grasa mínima.")
            else -> Pair("COMPLEXIÓN: EN ANÁLISIS", "Evaluando biotipo corporal según composición.")
        }
    }

    private fun evaluateFat(fatRate: Double, isMale: Boolean, age: Int): RangeResult {
        if (fatRate <= 0) return RangeResult("--", R.drawable.ic_fat_fitness, Color.GRAY)

        val (pBajo, pFitness, pAceptable) = if (isMale) {
            when {
                age <= 19 -> Triple(5.0, 16.0, 23.0)
                age <= 39 -> Triple(5.0, 17.0, 24.0)
                age <= 59 -> Triple(5.0, 19.0, 26.0)
                else -> Triple(5.0, 21.0, 29.0)
            }
        } else {
            when {
                age <= 19 -> Triple(13.0, 22.0, 29.0)
                age <= 39 -> Triple(13.0, 24.0, 32.0)
                age <= 59 -> Triple(13.0, 26.0, 34.0)
                else -> Triple(13.0, 27.0, 36.0)
            }
        }

        return when {
            fatRate <= pBajo -> RangeResult("BAJO / ESENCIAL", R.drawable.ic_fat_bajo, Color.parseColor("#E3B814"))
            fatRate <= pFitness -> RangeResult("FITNESS / ÓPTIMO", R.drawable.ic_fat_fitness, Color.parseColor("#32A852"))
            fatRate <= pAceptable -> RangeResult("ACEPTABLE / PROMEDIO", R.drawable.ic_fat_aceptable, Color.parseColor("#1E88E5"))
            else -> RangeResult("EXCESO / RIESGO", R.drawable.ic_fat_exceso, Color.parseColor("#D32F2F"))
        }
    }

    private fun evaluateMuscle(muscleRate: Double, isMale: Boolean, age: Int): RangeResult {
        if (muscleRate <= 0) return RangeResult("--", R.drawable.ic_muscle_saludable, Color.GRAY)

        val (pInsuf, pAcept, pSalud) = if (isMale) {
            when {
                age <= 19 -> Triple(65.0, 69.0, 76.0)
                age <= 39 -> Triple(68.0, 71.0, 79.0)
                age <= 59 -> Triple(64.0, 68.0, 76.0)
                else -> Triple(60.0, 64.0, 72.0)
            }
        } else {
            when {
                age <= 19 -> Triple(58.0, 61.0, 68.0)
                age <= 39 -> Triple(60.0, 64.0, 72.0)
                age <= 59 -> Triple(58.0, 60.0, 69.0)
                else -> Triple(52.0, 56.0, 65.0)
            }
        }

        return when {
            muscleRate < pInsuf -> RangeResult("INSUFICIENTE", R.drawable.ic_muscle_insuficiente, Color.parseColor("#E3B814"))
            muscleRate <= pAcept -> RangeResult("ACEPTABLE", R.drawable.ic_muscle_aceptable, Color.parseColor("#1E88E5"))
            muscleRate <= pSalud -> RangeResult("SALUDABLE", R.drawable.ic_muscle_saludable, Color.parseColor("#32A852"))
            else -> RangeResult("FITNESS / ATLÉTICO", R.drawable.ic_muscle_fitness, Color.parseColor("#2E7D32"))
        }
    }

    private fun evaluateVisceral(visceral: Double): RangeResult {
        if (visceral <= 0) return RangeResult("--", R.drawable.ic_visceral_normal, Color.GRAY)

        return when {
            visceral <= 9.0 -> RangeResult("NORMAL / SALUDABLE", R.drawable.ic_visceral_normal, Color.parseColor("#32A852"))
            visceral <= 14.0 -> RangeResult("ALTO / LÍMITE", R.drawable.ic_visceral_alto, Color.parseColor("#E37214"))
            else -> RangeResult("MUY ALTO / CRÍTICO", R.drawable.ic_visceral_critico, Color.parseColor("#D32F2F"))
        }
    }

    private fun evaluateWater(waterRate: Double, isMale: Boolean, age: Int): RangeResult {
        if (waterRate <= 0) return RangeResult("--", R.drawable.ic_water_optima, Color.GRAY)

        val (pDeshid, pBaja, pOptima) = if (isMale) {
            when {
                age <= 19 -> Triple(50.0, 55.0, 63.0)
                age <= 50 -> Triple(45.0, 53.0, 65.0)
                else -> Triple(40.0, 49.0, 60.0)
            }
        } else {
            when {
                age <= 19 -> Triple(45.0, 50.0, 58.0)
                age <= 50 -> Triple(40.0, 46.0, 57.0)
                else -> Triple(38.0, 42.0, 52.0)
            }
        }

        return when {
            waterRate < pDeshid -> RangeResult("DESHIDRATACIÓN", R.drawable.ic_water_deshidratacion, Color.parseColor("#D32F2F"))
            waterRate <= pBaja -> RangeResult("HIDRATACIÓN BAJA", R.drawable.ic_water_baja, Color.parseColor("#E37214"))
            waterRate <= pOptima -> RangeResult("HIDRATACIÓN ÓPTIMA", R.drawable.ic_water_optima, Color.parseColor("#32A852"))
            else -> RangeResult("HIPERHIDRATACIÓN", R.drawable.ic_water_hiperhidratacion, Color.parseColor("#1E88E5"))
        }
    }
}
