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
    var pbAlturaLoading: EqualizerLoaderView? = null
    var pbPesoLoading: EqualizerLoaderView? = null
    var pbImcLoading: EqualizerLoaderView? = null

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
    var pbPillProteinLoading: DotsLoaderView? = null
    var pbPillMineralLoading: DotsLoaderView? = null
    var pbPillMetabolismLoading: DotsLoaderView? = null
    var pbPillNotFatLoading: DotsLoaderView? = null

    var ivFatIcon: ImageView? = null
    var ivMuscleIcon: ImageView? = null
    var ivWaterIcon: ImageView? = null
    var ivVisceralIcon: ImageView? = null
    var pbFatLoading: EqualizerLoaderView? = null
    var pbMuscleLoading: EqualizerLoaderView? = null
    var pbWaterLoading: EqualizerLoaderView? = null
    var pbVisceralLoading: EqualizerLoaderView? = null

    // Temperatura
    var layoutResultTempContent: View? = null
    var ivTempIcon: ImageView? = null
    var tvResultTempMain: TextView? = null
    var tvResultTempSub: TextView? = null
    var tvTempStatus: TextView? = null
    var pbTemperaturaLoading: EqualizerLoaderView? = null

    // Oxígeno
    var ringSpO2: RingProgressView? = null
    var ringHeartRate: RingProgressView? = null
    var ringPI: RingProgressView? = null
    // Los íconos de SpO2/Ritmo/PI ahora se dibujan dentro del propio RingProgressView
    // (RingProgressView.setIcon), no como ImageView aparte.
    var pbSpO2Loading: EqualizerLoaderView? = null
    var pbHeartRateLoading: EqualizerLoaderView? = null
    var pbPILoading: EqualizerLoaderView? = null

    // Presión
    var tvResultSystolicValue: TextView? = null
    var tvResultDiastolicValue: TextView? = null
    var tvPresionStatus: TextView? = null
    var ringPresionPulse: RingProgressView? = null
    var ivSystolicIcon: ImageView? = null
    var ivDiastolicIcon: ImageView? = null
    // ivPresionPulseRingIcon también se dibuja ahora dentro del ring (RingProgressView.setIcon).
    var pbSistolicaLoading: EqualizerLoaderView? = null
    var pbDiastolicaLoading: EqualizerLoaderView? = null
    var pbPulsoLoading: EqualizerLoaderView? = null

    fun bindViews(root: View = activity.window.decorView) {
        // Altura / Peso / IMC
        tvResultHeightValue = root.findViewById(R.id.tvResultHeightValue)
        tvResultWeightValue = root.findViewById(R.id.tvResultWeightValue)
        tvResultIMCValue = root.findViewById(R.id.tvResultIMCValue)
        tvImcStatus = root.findViewById(R.id.tvImcStatus)
        gaugeIMC = root.findViewById(R.id.gaugeIMC)
        ivHeightIcon = root.findViewById(R.id.ivHeightIcon)
        ivWeightIcon = root.findViewById(R.id.ivWeightIcon)
        pbAlturaLoading = root.findViewById(R.id.pbAlturaLoading)
        pbPesoLoading = root.findViewById(R.id.pbPesoLoading)
        pbImcLoading = root.findViewById(R.id.pbImcLoading)

        gaugeIMC?.setGaugeType(GaugeView.GaugeType.IMC)
        gaugeIMC?.setValue(0.0, animated = false)
        gaugeIMC?.visibility = View.VISIBLE
        pbImcLoading?.visibility = View.VISIBLE
        tvResultIMCValue?.visibility = View.GONE

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
        pbPillProteinLoading = root.findViewById(R.id.pbPillProteinLoading)
        pbPillMineralLoading = root.findViewById(R.id.pbPillMineralLoading)
        pbPillMetabolismLoading = root.findViewById(R.id.pbPillMetabolismLoading)
        pbPillNotFatLoading = root.findViewById(R.id.pbPillNotFatLoading)

        ivFatIcon = root.findViewById(R.id.ivFatIcon)
        ivMuscleIcon = root.findViewById(R.id.ivMuscleIcon)
        ivWaterIcon = root.findViewById(R.id.ivWaterIcon)
        ivVisceralIcon = root.findViewById(R.id.ivVisceralIcon)
        pbFatLoading = root.findViewById(R.id.pbFatLoading)
        pbMuscleLoading = root.findViewById(R.id.pbMuscleLoading)
        pbWaterLoading = root.findViewById(R.id.pbWaterLoading)
        pbVisceralLoading = root.findViewById(R.id.pbVisceralLoading)

        // Temperatura
        layoutResultTempContent = root.findViewById(R.id.layoutResultTempContent)
        ivTempIcon = root.findViewById(R.id.ivTempIcon)
        tvResultTempMain = root.findViewById(R.id.tvResultTempMain)
        tvResultTempSub = root.findViewById(R.id.tvResultTempSub)
        tvTempStatus = root.findViewById(R.id.tvTempStatus)
        pbTemperaturaLoading = root.findViewById(R.id.pbTemperaturaLoading)

        // Oxígeno
        ringSpO2 = root.findViewById(R.id.ringSpO2)
        ringHeartRate = root.findViewById(R.id.ringHeartRate)
        ringPI = root.findViewById(R.id.ringPI)
        pbSpO2Loading = root.findViewById(R.id.pbSpO2Loading)
        pbHeartRateLoading = root.findViewById(R.id.pbHeartRateLoading)
        pbPILoading = root.findViewById(R.id.pbPILoading)

        // ringSpO2 y ringHeartRate ya no llevan un color fijo aquí: se recalcula por rango
        // de salud cada vez que llega un valor nuevo (ver updateOxygen). ringPI se queda
        // con un color fijo porque no se dio un rango para él.
        ringSpO2?.setIcon(androidx.core.content.ContextCompat.getDrawable(activity, R.drawable.ic_measurement_spo2))
        ringHeartRate?.setIcon(androidx.core.content.ContextCompat.getDrawable(activity, R.drawable.ic_measurement_heart_rate))
        ringPI?.setIcon(androidx.core.content.ContextCompat.getDrawable(activity, R.drawable.ic_measurement_pi))
        ringPI?.setColors(
            trackClr = Color.parseColor("#C8E6C9"),
            activeClr = Color.parseColor("#388E3C"),
            textClr = Color.parseColor("#2E7D32")
        )

        ringSpO2?.setData("SpO2", "", "%", 0f, animated = false, showContent = false)
        ringSpO2?.visibility = View.VISIBLE
        ringHeartRate?.setData("BPM", "", "", 0f, animated = false, showContent = false)
        ringHeartRate?.visibility = View.VISIBLE
        ringPI?.setData("PI %", "", "", 0f, animated = false, showContent = false)
        ringPI?.visibility = View.VISIBLE

        // Presión
        tvResultSystolicValue = root.findViewById(R.id.tvResultSystolicValue)
        tvResultDiastolicValue = root.findViewById(R.id.tvResultDiastolicValue)
        tvPresionStatus = root.findViewById(R.id.tvPresionStatus)
        ringPresionPulse = root.findViewById(R.id.ringPresionPulse)
        ivSystolicIcon = root.findViewById(R.id.ivSystolicIcon)
        ivDiastolicIcon = root.findViewById(R.id.ivDiastolicIcon)
        pbSistolicaLoading = root.findViewById(R.id.pbSistolicaLoading)
        pbDiastolicaLoading = root.findViewById(R.id.pbDiastolicaLoading)
        pbPulsoLoading = root.findViewById(R.id.pbPulsoLoading)

        ringPresionPulse?.setIcon(androidx.core.content.ContextCompat.getDrawable(activity, R.drawable.ic_measurement_heart_rate))
        ringPresionPulse?.setColors(
            trackClr = Color.parseColor("#FFCDD2"),
            activeClr = Color.parseColor("#E53935"),
            textClr = Color.parseColor("#E53935")
        )
        ringPresionPulse?.setData("BPM", "", "", 0f, animated = false, showContent = false)
        ringPresionPulse?.visibility = View.VISIBLE
    }

    // ── Colores por rango de salud ──────────────────────────────────
    private fun spo2RangeColor(spo2: Int): Int = when {
        spo2 >= 90 -> Color.parseColor("#32A852") // verde: 100-90
        spo2 >= 70 -> Color.parseColor("#FBC02D") // amarillo: 89-70
        else -> Color.parseColor("#D32F2F")       // rojo: menor a 70
    }

    private fun heartRateRangeColor(bpm: Int): Int = when {
        bpm in 60..70 -> Color.parseColor("#32A852") // verde: 60-70
        bpm in 71..90 -> Color.parseColor("#FBC02D") // amarillo: 70-90
        bpm > 90 -> Color.parseColor("#D32F2F")      // rojo: mas de 90
        else -> Color.parseColor("#D32F2F")          // menor a 60: tambien fuera de rango
    }

    fun updateHeightWeight(height: Double, weight: Double, imc: Double) {
        if (height > 0 && weight > 0) {
            tvResultHeightValue?.text = "%.1f".format(height)
            tvResultHeightValue?.visibility = View.VISIBLE
            ivHeightIcon?.visibility = View.VISIBLE
            pbAlturaLoading?.visibility = View.GONE

            tvResultWeightValue?.text = "%.1f".format(weight)
            tvResultWeightValue?.visibility = View.VISIBLE
            ivWeightIcon?.visibility = View.VISIBLE
            pbPesoLoading?.visibility = View.GONE

            tvResultIMCValue?.text = "%.1f".format(imc)
            tvResultIMCValue?.visibility = View.VISIBLE
            gaugeIMC?.setValue(imc, animated = true)
            gaugeIMC?.visibility = View.VISIBLE
            pbImcLoading?.visibility = View.GONE

            tvImcStatus?.visibility = View.GONE
        } else {
            tvResultHeightValue?.visibility = View.GONE
            ivHeightIcon?.visibility = View.GONE
            pbAlturaLoading?.visibility = View.VISIBLE

            tvResultWeightValue?.visibility = View.GONE
            ivWeightIcon?.visibility = View.GONE
            pbPesoLoading?.visibility = View.VISIBLE

            tvResultIMCValue?.visibility = View.GONE
            gaugeIMC?.setValue(0.0, animated = false)
            gaugeIMC?.visibility = View.VISIBLE
            pbImcLoading?.visibility = View.VISIBLE
            tvImcStatus?.visibility = View.GONE
        }
    }

    /** Recolorea los seis loaders de barritas (Altura/Peso/IMC/Sistólica/Diastólica/Pulso)
     *  con el color de marca del quiosco, igual que el resto de los indicadores de la pantalla. */
    fun applyLoaderColor(color: Int) {
        pbAlturaLoading?.setBarColor(color)
        pbPesoLoading?.setBarColor(color)
        pbImcLoading?.setBarColor(color)
        pbSistolicaLoading?.setBarColor(color)
        pbDiastolicaLoading?.setBarColor(color)
        pbPulsoLoading?.setBarColor(color)
        pbTemperaturaLoading?.setBarColor(color)
        pbFatLoading?.setBarColor(color)
        pbMuscleLoading?.setBarColor(color)
        pbWaterLoading?.setBarColor(color)
        pbVisceralLoading?.setBarColor(color)
        pbSpO2Loading?.setBarColor(color)
        pbHeartRateLoading?.setBarColor(color)
        pbPILoading?.setBarColor(color)
        pbPillProteinLoading?.setDotColor(color)
        pbPillMineralLoading?.setDotColor(color)
        pbPillMetabolismLoading?.setDotColor(color)
        pbPillNotFatLoading?.setDotColor(color)
        // ringSpO2 y ringHeartRate no se tocan aquí: su color depende del rango de salud
        // del valor medido (ver spo2RangeColor/heartRateRangeColor en updateOxygen), no
        // del color de marca del quiosco.
    }

    private fun lightenColor(color: Int, whiteRatio: Float): Int {
        val r = (Color.red(color) * (1 - whiteRatio) + 255 * whiteRatio).toInt().coerceIn(0, 255)
        val g = (Color.green(color) * (1 - whiteRatio) + 255 * whiteRatio).toInt().coerceIn(0, 255)
        val b = (Color.blue(color) * (1 - whiteRatio) + 255 * whiteRatio).toInt().coerceIn(0, 255)
        return Color.rgb(r, g, b)
    }

    fun clearComposition() {
        tvComplexionTitle?.text = "COMPLEXIÓN: EN ANÁLISIS"
        tvComplexionSub?.text = "Esperando resultados de composición..."

        ivFatIcon?.visibility = View.GONE
        tvResultFatRate?.visibility = View.GONE
        tvResultFatKg?.visibility = View.GONE
        pbFatLoading?.visibility = View.VISIBLE

        ivMuscleIcon?.visibility = View.GONE
        tvResultMuscleRate?.visibility = View.GONE
        tvResultMuscleKg?.visibility = View.GONE
        pbMuscleLoading?.visibility = View.VISIBLE

        ivWaterIcon?.visibility = View.GONE
        tvResultWaterRate?.visibility = View.GONE
        tvResultWaterKg?.visibility = View.GONE
        pbWaterLoading?.visibility = View.VISIBLE

        ivVisceralIcon?.visibility = View.GONE
        tvResultVisceralFat?.visibility = View.GONE
        pbVisceralLoading?.visibility = View.VISIBLE

        tvPillProtein?.visibility = View.GONE
        pbPillProteinLoading?.visibility = View.VISIBLE

        tvPillMineral?.visibility = View.GONE
        pbPillMineralLoading?.visibility = View.VISIBLE

        tvPillMetabolism?.visibility = View.GONE
        pbPillMetabolismLoading?.visibility = View.VISIBLE

        tvPillNotFat?.visibility = View.GONE
        pbPillNotFatLoading?.visibility = View.VISIBLE
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
        pbFatLoading?.visibility = View.GONE

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
        pbMuscleLoading?.visibility = View.GONE

        // 4. Agua Corporal
        tvResultWaterRate?.text = "%.0f%%".format(state.waterRate)
        tvResultWaterRate?.visibility = View.VISIBLE
        tvResultWaterKg?.text = "%.1f Kg".format(state.waterKg)
        tvResultWaterKg?.visibility = View.VISIBLE
        val waterResult = evaluateWater(state.waterRate, isMale, age)
        ivWaterIcon?.setImageResource(waterResult.iconResId)
        ivWaterIcon?.visibility = View.VISIBLE
        pbWaterLoading?.visibility = View.GONE

        // 5. Grasa Visceral
        tvResultVisceralFat?.text = "%.1f".format(state.visceralFat)
        tvResultVisceralFat?.visibility = View.VISIBLE
        val visceralResult = evaluateVisceral(state.visceralFat)
        ivVisceralIcon?.setImageResource(visceralResult.iconResId)
        ivVisceralIcon?.visibility = View.VISIBLE
        pbVisceralLoading?.visibility = View.GONE

        // Píldoras / Tarjetas secundarias
        tvPillProtein?.text = "%.1f kg - 15 %%".format(state.protein)
        tvPillProtein?.visibility = View.VISIBLE
        pbPillProteinLoading?.visibility = View.GONE

        tvPillMineral?.text = "%.1f kg - 15 %%".format(state.mineral)
        tvPillMineral?.visibility = View.VISIBLE
        pbPillMineralLoading?.visibility = View.GONE

        tvPillMetabolism?.text = "${state.metabolism} Kcal"
        tvPillMetabolism?.visibility = View.VISIBLE
        pbPillMetabolismLoading?.visibility = View.GONE

        tvPillNotFat?.text = "%.1f kg".format(state.notFat)
        tvPillNotFat?.visibility = View.VISIBLE
        pbPillNotFatLoading?.visibility = View.GONE
    }

    fun updateTemperature(tempC: Double, tempF: Double) {
        if (tempC > 0) {
            tvResultTempMain?.text = "%.1f °C".format(tempC)
            val subF = if (tempF > 0) tempF else (tempC * 9 / 5 + 32)
            tvResultTempSub?.text = "%.2f °F".format(subF)

            layoutResultTempContent?.visibility = View.VISIBLE
            pbTemperaturaLoading?.visibility = View.GONE
        } else {
            layoutResultTempContent?.visibility = View.GONE
            tvTempStatus?.visibility = View.GONE
            pbTemperaturaLoading?.visibility = View.VISIBLE
        }
    }

    fun updateOxygen(spo2: Int, pulseRate: Int, pi: Double) {
        if (spo2 > 0) {
            val spo2Color = spo2RangeColor(spo2)
            ringSpO2?.setColors(trackClr = lightenColor(spo2Color, 0.75f), activeClr = spo2Color, textClr = spo2Color)
            val spo2Ratio = (spo2 / 100f).coerceIn(0f, 1f)
            ringSpO2?.setData("SpO2", "$spo2", "%", spo2Ratio, animated = true, showContent = true)
            ringSpO2?.visibility = View.VISIBLE
            pbSpO2Loading?.visibility = View.GONE

            val pulseRatio = if (pulseRate > 0) (pulseRate / 150f).coerceIn(0f, 1f) else 0f
            val pulseValStr = if (pulseRate > 0) "$pulseRate" else "--"
            if (pulseRate > 0) {
                val bpmColor = heartRateRangeColor(pulseRate)
                ringHeartRate?.setColors(trackClr = lightenColor(bpmColor, 0.75f), activeClr = bpmColor, textClr = bpmColor)
            }
            ringHeartRate?.setData("BPM", pulseValStr, "", pulseRatio, animated = true, showContent = true)
            ringHeartRate?.visibility = View.VISIBLE
            pbHeartRateLoading?.visibility = View.GONE

            val piRatio = if (pi > 0) (pi / 20.0).toFloat().coerceIn(0f, 1f) else 0f
            val piValStr = if (pi > 0) "%.1f".format(pi) else "--"
            ringPI?.setData("PI %", piValStr, "", piRatio, animated = true, showContent = true)
            ringPI?.visibility = View.VISIBLE
            pbPILoading?.visibility = View.GONE
        } else {
            ringSpO2?.setData("SpO2", "", "%", 0f, animated = false, showContent = false)
            ringSpO2?.visibility = View.VISIBLE
            pbSpO2Loading?.visibility = View.VISIBLE

            ringHeartRate?.setData("BPM", "", "", 0f, animated = false, showContent = false)
            ringHeartRate?.visibility = View.VISIBLE
            pbHeartRateLoading?.visibility = View.VISIBLE

            ringPI?.setData("PI %", "", "", 0f, animated = false, showContent = false)
            ringPI?.visibility = View.VISIBLE
            pbPILoading?.visibility = View.VISIBLE
        }
    }

    fun updatePressure(systolic: Int, diastolic: Int, pulse: Int) {
        if (systolic > 0 && diastolic > 0) {
            tvResultSystolicValue?.text = "$systolic"
            tvResultSystolicValue?.visibility = View.VISIBLE
            ivSystolicIcon?.visibility = View.VISIBLE
            pbSistolicaLoading?.visibility = View.GONE

            tvResultDiastolicValue?.text = "$diastolic"
            tvResultDiastolicValue?.visibility = View.VISIBLE
            ivDiastolicIcon?.visibility = View.VISIBLE
            pbDiastolicaLoading?.visibility = View.GONE

            val pulseRatio = if (pulse > 0) (pulse / 150f).coerceIn(0f, 1f) else 0f
            val pulseValStr = if (pulse > 0) "$pulse" else "--"
            ringPresionPulse?.setData("BPM", pulseValStr, "", pulseRatio, animated = true, showContent = true)
            ringPresionPulse?.visibility = View.VISIBLE
            pbPulsoLoading?.visibility = View.GONE
        } else {
            tvResultSystolicValue?.visibility = View.GONE
            ivSystolicIcon?.visibility = View.GONE
            pbSistolicaLoading?.visibility = View.VISIBLE

            tvResultDiastolicValue?.visibility = View.GONE
            ivDiastolicIcon?.visibility = View.GONE
            pbDiastolicaLoading?.visibility = View.VISIBLE

            ringPresionPulse?.setData("BPM", "", "", 0f, animated = false, showContent = false)
            ringPresionPulse?.visibility = View.VISIBLE
            pbPulsoLoading?.visibility = View.VISIBLE
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
