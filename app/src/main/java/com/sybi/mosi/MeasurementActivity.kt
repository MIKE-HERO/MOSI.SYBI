package com.sybi.mosi

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.view.animation.OvershootInterpolator
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaPlayer
import android.os.Bundle
import android.os.CountDownTimer
import android.util.Log
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.sybi.mosi.database.AppDatabase
import com.sybi.mosi.database.Resultado
import com.sybi.mosi.helpers.HWFatHelper
import com.sybi.mosi.measurement.*
import com.sybi.mosi.measurement.EcgStorage
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking




class MeasurementActivity : BaseActivity(), MeasurementController.Callbacks {

    companion object {
        private const val TAG = "MeasurementActivity"
        private const val ERROR_COLOR = "#F44336"
    }

    // ── Estado y managers ─────────────────────────────────
    private val state = MeasurementState()
    private var firstButtonTriggered = false
    private val hwFatHelper = HWFatHelper(this)
    private lateinit var deviceManager: DeviceConnectionManager
    private lateinit var controller: MeasurementController
    private lateinit var ecgManager: EcgManager

    // ── Datos del paciente ────────────────────────────────
    private var pacienteId = 0
    private var pacienteIdLocal = 0L
    private var pacienteNombre = ""
    private var pacienteFechaNacimiento = ""
    private var pacienteGenero = "M"
    private var pacienteTelefono = ""

    // ── Vistas ────────────────────────────────────────────
    private lateinit var tvTitle: TextView
    private lateinit var tvResultHeight: TextView
    private lateinit var tvResultWeight: TextView
    private lateinit var tvResultIMC: TextView
    private lateinit var tvResultTemperature: TextView
    private lateinit var tvResultFat: TextView
    private lateinit var tvResultFatKg: TextView
    private lateinit var tvResultWater: TextView
    private lateinit var tvResultWaterKg: TextView
    private lateinit var tvResultMuscle: TextView
    private lateinit var tvResultNotFat: TextView
    private lateinit var tvResultProtein: TextView
    private lateinit var tvResultMineral: TextView
    private lateinit var tvResultMetabolism: TextView
    private lateinit var tvResultVisceralFat: TextView
    private lateinit var tvResultIdealWeight: TextView
    private lateinit var tvResultFatType: TextView
    private lateinit var tvResultSistolica: TextView
    private lateinit var tvResultDiastolica: TextView
    private lateinit var tvResultPulso: TextView
    private lateinit var tvResultSpO2: TextView
    private lateinit var tvResultPulseRate: TextView
    private lateinit var tvResultPI: TextView

    // ÚLTIMOS RESULTADOS (Historial)
    private lateinit var layoutLastResults: LinearLayout
    private lateinit var lastGroupAlturaPeso: LinearLayout
    private lateinit var lastGroupPresion: LinearLayout
    private lateinit var lastGroupOxigeno: LinearLayout
    private lateinit var lastGroupComposicion: LinearLayout
    private lateinit var lastGroupEcg: LinearLayout
    private lateinit var tvLastHeight: TextView
    private lateinit var tvLastWeight: TextView
    private lateinit var tvLastIMC: TextView
    private lateinit var tvLastSistolica: TextView
    private lateinit var tvLastDiastolica: TextView
    private lateinit var tvLastPulso: TextView
    private lateinit var tvLastSpO2: TextView
    private lateinit var tvLastPulseRate: TextView
    private lateinit var tvLastPI: TextView
    private lateinit var tvLastTemperature: TextView
    private lateinit var tvLastFat: TextView
    private lateinit var tvLastMuscle: TextView
    private lateinit var tvLastMetabolism: TextView
    private lateinit var tvLastEcgHeartRate: TextView
    private lateinit var tvLastEcgQRS: TextView
    private lateinit var tvLastEcgResult: TextView

    // ECG
    private lateinit var layoutResultadosEcg: LinearLayout
    private lateinit var tvResultHeartRate: TextView
    private lateinit var tvResultPAxis: TextView
    private lateinit var tvResultQRSAxis: TextView
    private lateinit var tvResultTAxis: TextView
    private lateinit var tvResultPR: TextView
    private lateinit var tvResultQRS: TextView
    private lateinit var tvResultQT: TextView
    private lateinit var tvResultQTC: TextView
    private lateinit var tvResultRV5: TextView
    private lateinit var tvResultSV1: TextView
    private lateinit var tvResultResCode: TextView
    private lateinit var ecgImageView: ImageView
    private lateinit var cardEcgImage: CardView

    private lateinit var imgGuide: ImageView
    private var guideBreathingAnimator: android.animation.ObjectAnimator? = null
    private lateinit var waveformView: WaveformView
    private lateinit var tvStatusMessage: TextView
    private lateinit var ivStatusCheck: ImageView
    private lateinit var statusSweep: LoadingSweepView
    private var activeResultPills: List<TextView> = emptyList()
    private var pillPulseAnimator: ValueAnimator? = null
    private var pillLightColor: Int = Color.parseColor("#E3EAF7")
    private var pillPulseColor: Int = Color.parseColor("#B9CCEE")
    private lateinit var groupAlturaPeso: LinearLayout
    private lateinit var groupTemperatura: LinearLayout
    private lateinit var groupComposicion: LinearLayout
    private lateinit var groupPresion: LinearLayout
    private lateinit var groupOxigeno: LinearLayout
    private lateinit var groupEcg: LinearLayout
    private lateinit var groupAzucar: LinearLayout
    private lateinit var groupAcidoUrico: LinearLayout
    private lateinit var groupColesterol: LinearLayout

    // Progreso composición
    private lateinit var layoutProgressComposicion: LinearLayout
    private lateinit var layoutResultadosComposicion: LinearLayout
    private lateinit var pbComposicion: ProgressBar
    private lateinit var tvProgressPercent: TextView

    private lateinit var btnStarECG: Button
    private lateinit var btnConfirmMeasurements: Button
    private lateinit var btnRepeat: Button
    private lateinit var tvCountdownMessage: TextView
    private var countdownTimer: CountDownTimer? = null
    private var mediaPlayer: MediaPlayer? = null

    private lateinit var allButtons: List<View>
    private var currentMeasurementType = ""
    private val completedMeasurements = mutableSetOf<String>()
    private var currentPlayingAudioName: String? = null
    private var isActivityResumed = false

    // ── Receivers de datos ────────────────────────────────
    private val dataReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            handleDeviceData(intent)
        }
    }

    private val waveReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action == "OXYGEN_WAVE_DATA" && currentMeasurementType == "OXIGENO") {
                waveformView.addPoint(intent.getIntExtra("value", 0))
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)
        setContentView(R.layout.activity_measurement)

        readPatientIntent()
        bindViews()
        setupManagers()
        setupReceivers()
        setupButtons()
        applyTheme()
        startGuideBreathing()

        controller.postDelayed({
            deviceManager.query()
        }, 300)
    }

    // ── Setup ─────────────────────────────────────────────

    private fun readPatientIntent() {
        pacienteId = intent.getIntExtra("id_usuario_web", 0)
        pacienteIdLocal = intent.getLongExtra("id_local", 0L)
        pacienteNombre = intent.getStringExtra("nombre") ?: ""
        pacienteTelefono = intent.getStringExtra("telefono") ?: ""
        pacienteFechaNacimiento = intent.getStringExtra("fecha_nacimiento") ?: ""
        pacienteGenero = intent.getStringExtra("genero") ?: "M"

        if (pacienteId == 0 && pacienteTelefono.isNotEmpty()) {
            Thread {
                runBlocking {
                    val p = AppDatabase.getInstance(this@MeasurementActivity)
                        .pacienteDao().obtenerPacientePorTelefono(pacienteTelefono)
                    p?.let {
                        pacienteId = it.id_usuario_web ?: 0
                        pacienteIdLocal = it.id_local
                        pacienteNombre = it.nombre
                        pacienteFechaNacimiento = it.fecha_nacimiento
                        pacienteGenero = it.genero
                    }
                }
            }.start()
        }
    }

    private fun bindViews() {
        tvTitle = findViewById(R.id.tvMeasurementTitle)
        tvResultHeight = findViewById(R.id.tvResultHeight)
        tvResultWeight = findViewById(R.id.tvResultWeight)
        tvResultIMC = findViewById(R.id.tvResultIMC)
        tvResultTemperature = findViewById(R.id.tvResultTemperature)
        tvResultFat = findViewById(R.id.tvResultFat)
        tvResultFatKg = findViewById(R.id.tvResultFatKg)
        tvResultWater = findViewById(R.id.tvResultWater)
        tvResultWaterKg = findViewById(R.id.tvResultWaterKg)
        tvResultMuscle = findViewById(R.id.tvResultMuscle)
        tvResultNotFat = findViewById(R.id.tvResultNotFat)
        tvResultProtein = findViewById(R.id.tvResultProtein)
        tvResultMineral = findViewById(R.id.tvResultMineral)
        tvResultMetabolism = findViewById(R.id.tvResultMetabolism)
        tvResultVisceralFat = findViewById(R.id.tvResultVisceralFat)
        tvResultIdealWeight = findViewById(R.id.tvResultIdealWeight)
        tvResultFatType = findViewById(R.id.tvResultFatType)
        tvResultSistolica = findViewById(R.id.tvResultSistolica)
        tvResultDiastolica = findViewById(R.id.tvResultDiastolica)
        tvResultPulso = findViewById(R.id.tvResultPulso)
        tvResultSpO2 = findViewById(R.id.tvResultSpO2)
        tvResultPulseRate = findViewById(R.id.tvResultPulseRate)
        tvResultPI = findViewById(R.id.tvResultPI)

        // Binding últimos resultados
        layoutLastResults = findViewById(R.id.layoutLastResults)
        lastGroupAlturaPeso = findViewById(R.id.lastGroupAlturaPeso)
        lastGroupPresion = findViewById(R.id.lastGroupPresion)
        lastGroupOxigeno = findViewById(R.id.lastGroupOxigeno)
        lastGroupComposicion = findViewById(R.id.lastGroupComposicion)
        lastGroupEcg = findViewById(R.id.lastGroupEcg)
        tvLastHeight = findViewById(R.id.tvLastHeight)
        tvLastWeight = findViewById(R.id.tvLastWeight)
        tvLastIMC = findViewById(R.id.tvLastIMC)
        tvLastSistolica = findViewById(R.id.tvLastSistolica)
        tvLastDiastolica = findViewById(R.id.tvLastDiastolica)
        tvLastPulso = findViewById(R.id.tvLastPulso)
        tvLastSpO2 = findViewById(R.id.tvLastSpO2)
        tvLastPulseRate = findViewById(R.id.tvLastPulseRate)
        tvLastPI = findViewById(R.id.tvLastPI)
        tvLastTemperature = findViewById(R.id.tvLastTemperature)
        tvLastFat = findViewById(R.id.tvLastFat)
        tvLastMuscle = findViewById(R.id.tvLastMuscle)
        tvLastMetabolism = findViewById(R.id.tvLastMetabolism)
        tvLastEcgHeartRate = findViewById(R.id.tvLastEcgHeartRate)
        tvLastEcgQRS = findViewById(R.id.tvLastEcgQRS)
        tvLastEcgResult = findViewById(R.id.tvLastEcgResult)

        layoutResultadosEcg = findViewById(R.id.layoutResultadosEcg)
        tvResultHeartRate = findViewById(R.id.tvResultHeartRate)
        tvResultPAxis = findViewById(R.id.tvResultPAxis)
        tvResultQRSAxis = findViewById(R.id.tvResultQRSAxis)
        tvResultTAxis = findViewById(R.id.tvResultTAxis)
        tvResultPR = findViewById(R.id.tvResultPR)
        tvResultQRS = findViewById(R.id.tvResultQRS)
        tvResultQT = findViewById(R.id.tvResultQT)
        tvResultQTC = findViewById(R.id.tvResultQTC)
        tvResultRV5 = findViewById(R.id.tvResultRV5)
        tvResultSV1 = findViewById(R.id.tvResultSV1)
        tvResultResCode = findViewById(R.id.tvResultResCode)
        ecgImageView = findViewById(R.id.ecgImageView)
        cardEcgImage = findViewById(R.id.cardEcgImage)
        imgGuide = findViewById(R.id.imgMeasurementGuide)
        waveformView = findViewById(R.id.waveformView)
        tvStatusMessage = findViewById(R.id.tvStatusMessage)
        ivStatusCheck = findViewById(R.id.ivStatusCheck)
        statusSweep = findViewById(R.id.statusSweep)
        groupAlturaPeso = findViewById(R.id.groupAlturaPeso)
        groupTemperatura = findViewById(R.id.groupTemperatura)
        groupComposicion = findViewById(R.id.groupComposicion)
        groupPresion = findViewById(R.id.groupPresion)
        groupOxigeno = findViewById(R.id.groupOxigeno)
        groupEcg = findViewById(R.id.groupEcg)
        groupAzucar = findViewById(R.id.groupAzucar)
        groupAcidoUrico = findViewById(R.id.groupAcidoUrico)
        groupColesterol = findViewById(R.id.groupColesterol)

        layoutProgressComposicion = findViewById(R.id.layoutProgressComposicion)
        layoutResultadosComposicion = findViewById(R.id.layoutResultadosComposicion)
        pbComposicion = findViewById(R.id.pbComposicion)
        tvProgressPercent = findViewById(R.id.tvProgressPercent)

        btnStarECG = findViewById(R.id.btnStarECG)
        btnConfirmMeasurements = findViewById(R.id.btnConfirmMeasurements)
        btnRepeat = findViewById(R.id.btnRepeatReading)
        tvCountdownMessage = findViewById(R.id.tvCountdownMessage)
        btnConfirmMeasurements.visibility = View.GONE
    }

    private fun setupManagers() {
        deviceManager = DeviceConnectionManager(this) {
            runOnUiThread {
                updateDeviceStatusMessage()
                // ✅ Si es la primera vez que llega la respuesta, dispara el primer botón
                if (!firstButtonTriggered && !isFinishing) {
                    firstButtonTriggered = true
                    findFirstActiveButtonAndClick()
                }
            }
        }

        controller = MeasurementController(this, state, deviceManager, this)

        ecgManager = EcgManager(
            activity = this,
            onResult = { results, bitmap ->
                state.ecgResults = results
                state.ecgImage = bitmap
                showEcgResults(results, bitmap)
                btnStarECG.visibility = View.GONE
                btnRepeat.visibility = View.VISIBLE
                onMeasurementComplete()
            },
            onError = {
                Toast.makeText(this, "Error en la medición de ECG", Toast.LENGTH_LONG).show()
                onMeasurementError()
                btnRepeat.visibility = View.VISIBLE
                btnStarECG.visibility = View.GONE
            }
        )
        ecgManager.init()

        cardEcgImage.setOnClickListener {
            state.ecgImage?.let { showFullScreenImage(it) }
        }

        deviceManager.register()
    }

    private fun setupReceivers() {
        LocalBroadcastManager.getInstance(this).apply {
            registerReceiver(dataReceiver, IntentFilter("DEVICE_DATA_RECEIVED"))
            registerReceiver(waveReceiver, IntentFilter("OXYGEN_WAVE_DATA"))
        }
    }

    private fun applyTheme() {
        val saved = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
            .getString("BackgroundColor", "#0F3E82") ?: "#0F3E82"
        val color = Color.parseColor(saved)
        findViewById<View>(R.id.topMeasurementBar).setBackgroundColor(color)
        findViewById<View>(R.id.bottomMeasurementBar).setBackgroundColor(color)
        btnStarECG.backgroundTintList = android.content.res.ColorStateList.valueOf(color)
        applyPillTheme(color)
    }

    /** Encapsula los valores de resultado y el mensaje de estado en píldoras con un tono claro
     *  alusivo al color del tema (logo/menú), en todas las vistas de medición, y prepara los
     *  colores del barrido de carga y del pulso de las píldoras en espera. */
    private fun applyPillTheme(themeColor: Int) {
        pillLightColor = lightenColor(themeColor, 0.85f)
        pillPulseColor = lightenColor(themeColor, 0.55f)

        val allResultPills = listOf(
            tvResultHeight, tvResultWeight, tvResultIMC,
            tvResultTemperature,
            tvResultSistolica, tvResultDiastolica, tvResultPulso,
            tvResultSpO2, tvResultPulseRate, tvResultPI,
            tvResultFat, tvResultFatKg, tvResultWater, tvResultWaterKg, tvResultMuscle,
            tvResultNotFat, tvResultProtein, tvResultMineral, tvResultMetabolism,
            tvResultVisceralFat, tvResultIdealWeight, tvResultFatType,
            tvResultHeartRate, tvResultPAxis, tvResultQRSAxis, tvResultTAxis, tvResultPR,
            tvResultQRS, tvResultQT, tvResultQTC, tvResultRV5, tvResultSV1, tvResultResCode
        )
        allResultPills.forEach { it.background = pillDrawable(pillLightColor, 18f) }

        tvStatusMessage.background = pillDrawable(pillLightColor, 28f)
        tvStatusMessage.setPadding(dp(20), dp(12), dp(20), dp(12))
        statusSweep.setCornerRadiusDp(28f)
        statusSweep.setSweepColor(themeColor)
    }

    /** Píldoras de resultado de la vista activa que deben "pulsar" mientras se espera su valor. */
    private fun setActiveResultPills(vararg views: TextView) {
        activeResultPills = views.toList()
    }

    private fun startPillPulse() {
        if (pillPulseAnimator != null || activeResultPills.isEmpty()) return
        pillPulseAnimator = ValueAnimator.ofObject(ArgbEvaluator(), pillLightColor, pillPulseColor).apply {
            duration = 900
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { anim ->
                val color = anim.animatedValue as Int
                activeResultPills.forEach { (it.background as? android.graphics.drawable.GradientDrawable)?.setColor(color) }
            }
            start()
        }
    }

    private fun stopPillPulse() {
        pillPulseAnimator?.cancel()
        pillPulseAnimator = null
        activeResultPills.forEach { (it.background as? android.graphics.drawable.GradientDrawable)?.setColor(pillLightColor) }
    }

    private fun lightenColor(color: Int, whiteRatio: Float): Int {
        val r = (Color.red(color) * (1 - whiteRatio) + 255 * whiteRatio).toInt().coerceIn(0, 255)
        val g = (Color.green(color) * (1 - whiteRatio) + 255 * whiteRatio).toInt().coerceIn(0, 255)
        val b = (Color.blue(color) * (1 - whiteRatio) + 255 * whiteRatio).toInt().coerceIn(0, 255)
        return Color.rgb(r, g, b)
    }

    private fun pillDrawable(fillColor: Int, radiusDp: Float): android.graphics.drawable.GradientDrawable {
        return android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = radiusDp * resources.displayMetrics.density
            setColor(fillColor)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    // ── Botones ───────────────────────────────────────────

    private fun setupButtons() {
        val btnAlturaPeso = findViewById<LinearLayout>(R.id.btnNavAlturaPeso)
        val btnComposicion = findViewById<LinearLayout>(R.id.btnNavComposicion)
        val btnPresion = findViewById<LinearLayout>(R.id.btnNavPresion)
        val btnTemperatura = findViewById<LinearLayout>(R.id.btnNavTemperatura)
        val btnOxigeno = findViewById<LinearLayout>(R.id.btnNavOxigeno)
        val btnEcg = findViewById<LinearLayout>(R.id.btnNavEcg)
        val btnAzucar = findViewById<LinearLayout>(R.id.btnNavAzucar)
        val btnAcidoUrico = findViewById<LinearLayout>(R.id.btnNavAcidoUrico)
        val btnColesterol = findViewById<LinearLayout>(R.id.btnNavColesterol)

        allButtons = listOf(btnAlturaPeso, btnComposicion, btnPresion, btnTemperatura,
            btnOxigeno, btnEcg, btnAzucar, btnAcidoUrico, btnColesterol)

        val prefs = getSharedPreferences("DevicePrefs", Context.MODE_PRIVATE)
        fun active(k: String) = prefs.getBoolean(k, true)
        if (!active("device_altura_peso")) btnAlturaPeso.visibility = View.GONE
        if (!active("device_composicion")) btnComposicion.visibility = View.GONE
        if (!active("device_presion")) btnPresion.visibility = View.GONE
        if (!active("device_temperatura")) btnTemperatura.visibility = View.GONE
        if (!active("device_oxigeno")) btnOxigeno.visibility = View.GONE
        if (!active("device_ecg")) btnEcg.visibility = View.GONE
        if (!active("device_azucar")) btnAzucar.visibility = View.GONE
        if (!active("device_acido_urico")) btnAcidoUrico.visibility = View.GONE
        if (!active("device_colesterol")) btnColesterol.visibility = View.GONE

        btnAlturaPeso.setOnClickListener { startHeightWeight() }
        btnComposicion.setOnClickListener { startComposition() }
        btnPresion.setOnClickListener { startPressure() }
        btnTemperatura.setOnClickListener { startTemperature() }
        btnOxigeno.setOnClickListener { startOxygen() }
        btnEcg.setOnClickListener { showEcgTab() }
        btnStarECG.setOnClickListener { btnStarECG.visibility = View.GONE; startEcg() }
        btnAzucar.setOnClickListener { showUnavailable("Azúcar en Sangre", "azucar") }
        btnAcidoUrico.setOnClickListener { showUnavailable("Ácido Úrico", "acido_urico") }
        btnColesterol.setOnClickListener { showUnavailable("Colesterol Total", "colesterol") }
        btnRepeat.setOnClickListener { repeatCurrent() }

        findViewById<TextView>(R.id.btnExitMeasurement).setOnClickListener { finish() }
        btnConfirmMeasurements.setOnClickListener {
            btnConfirmMeasurements.isEnabled = false
            btnConfirmMeasurements.text = "Guardando..."

            Thread {
                // Todo el trabajo pesado en background
                guardarResultadosEnBD()
                saveAllResults()

                runOnUiThread {
                    btnConfirmMeasurements.isEnabled = true
                    btnConfirmMeasurements.text = "Confirmar"
                    startActivity(Intent(this, ResultsActivity::class.java)
                        .putExtra("id_local", pacienteIdLocal))
                }
            }.start()
        }
    }

    // ── Flujos de medición ────────────────────────────────

    private fun startHeightWeight() {
        currentMeasurementType = "ALTURA_PESO"
        switchTab("Altura / Peso", "altura_peso", groupAlturaPeso)
        setActiveResultPills(tvResultHeight, tvResultWeight, tvResultIMC)

        if (completedMeasurements.contains("ALTURA_PESO") || state.hasHeightWeight()) {
            btnRepeat.visibility = View.VISIBLE
            return
        }
        showReady()
        controller.sendCommand("ALTURA_PESO")
    }

    private fun startComposition() {
        currentMeasurementType = "COMPOSICION"
        switchTab("Composición Corporal", "composicion", groupComposicion)
        setActiveResultPills()

        // Asegurar que el grupo esté visible y ocultar las celdas de resultado para mostrar el progreso
        layoutResultadosComposicion.visibility = View.GONE

        if (completedMeasurements.contains("COMPOSICION") || state.hasComposition()) {
            layoutProgressComposicion.visibility = View.GONE
            layoutResultadosComposicion.visibility = View.VISIBLE
            btnRepeat.visibility = View.VISIBLE
            return
        }
        if (!state.hasHeightWeight()) {
            Toast.makeText(this, "⚠️ Primero mide altura y peso", Toast.LENGTH_LONG).show()
            onMeasurementError(); return
        }

        // Preparar barra de progreso y ocultar resultados
        pbComposicion.progress = 0
        tvProgressPercent.text = "0%"
        layoutProgressComposicion.visibility = View.VISIBLE

        sendPatientDataToService()
        showReady()
        controller.sendCommand("COMPOSICION")
    }

    private fun startPressure() {
        currentMeasurementType = "PRESION"
        switchTab("Presión Arterial", "presion", groupPresion)
        setActiveResultPills(tvResultSistolica, tvResultDiastolica, tvResultPulso)

        if (completedMeasurements.contains("PRESION") || state.hasPressure()) {
            btnRepeat.visibility = View.VISIBLE
            return
        }
        showReady()
        controller.sendCommand("PRESION")
    }

    private fun startTemperature() {
        currentMeasurementType = "TEMPERATURA"
        switchTab("Temperatura Corporal", "temperatura", groupTemperatura)
        setActiveResultPills(tvResultTemperature)

        if (completedMeasurements.contains("TEMPERATURA") || state.hasTemperature()) {
            btnRepeat.visibility = View.VISIBLE
            return
        }
        showReady()
        controller.sendCommand("TEMPERATURA")
    }

    private fun startOxygen() {
        currentMeasurementType = "OXIGENO"
        switchTab("Oxígeno en Sangre", "oxigeno", groupOxigeno)
        setActiveResultPills(tvResultSpO2, tvResultPulseRate, tvResultPI)

        if (completedMeasurements.contains("OXIGENO") || state.hasOxygen()) {
            btnRepeat.visibility = View.VISIBLE
            return
        }
        waveformView.clear()
        waveformView.visibility = View.VISIBLE
        controller.startOxygenMeasurement()
    }

    private fun showEcgTab() {
        currentMeasurementType = "ECG"
        switchTab("ECG", "ecg", groupEcg)
        setActiveResultPills()

        // Asegurar que groupEcg esté visible pero layoutResultadosEcg esté oculto inicialmente
        layoutResultadosEcg.visibility = View.GONE

        if (completedMeasurements.contains("ECG") || state.hasEcg()) {
            state.ecgResults?.let { showEcgResults(it, state.ecgImage!!) }
            btnStarECG.visibility = View.GONE
            btnRepeat.visibility = View.VISIBLE
        } else {
            showStatusMessage("Presione el botón ECG para iniciar la medición", "#FF9800")
            btnStarECG.visibility = View.VISIBLE
            btnRepeat.visibility = View.GONE
        }
    }

    private fun showUnavailable(title: String, key: String) {
        currentMeasurementType = key.uppercase()
        switchTab(title, key, null)
        btnStarECG.visibility = View.GONE
        btnRepeat.visibility = View.GONE
        showStatusMessage("Función no disponible", "#FF9800")
    }

    private fun startEcg() {
        val ok = ecgManager.startMeasurement(
            pacienteNombre, pacienteId.toString(), pacienteGenero, pacienteFechaNacimiento
        )
        if (!ok) {
            Toast.makeText(this, "No se pudo encontrar la app de ECG", Toast.LENGTH_LONG).show()
            onMeasurementError()
        }
    }

    private fun repeatCurrent() {
        cancelCountdownTimer()
        setRepeatMode(false)
        completedMeasurements.remove(currentMeasurementType)
        getAudioName(currentMeasurementType, "start")?.let { playAudio(it, forceRestart = true) }
        if (!deviceManager.isAvailableFor(currentMeasurementType)) {
            showStatusMessage("Error: no se encuentra el dispositivo", "#F44336")
            setRepeatMode(true)
            return
        }
        when (currentMeasurementType) {
            "ALTURA_PESO" -> {
                state.resetHeightWeight()
                tvResultHeight.text = "Altura\n--"
                tvResultWeight.text = "Peso\n--"
                tvResultIMC.text = "IMC\n--"
                showReady(); controller.forceSendCommand("ALTURA_PESO")
            }
            "COMPOSICION" -> {
                if (!state.hasHeightWeight()) {
                    Toast.makeText(this, "⚠️ Primero mide altura y peso", Toast.LENGTH_LONG).show()
                    return
                }
                state.resetComposition()
                layoutResultadosComposicion.visibility = View.GONE

                // Reiniciar progreso
                pbComposicion.progress = 0
                tvProgressPercent.text = "0%"
                layoutProgressComposicion.visibility = View.VISIBLE

                sendPatientDataToService(); showReady(); controller.forceSendCommand("COMPOSICION")
            }
            "PRESION" -> {
                state.resetPressure()
                tvResultSistolica.text = "Sistólica\n-- mmHg"
                tvResultDiastolica.text = "Diastólica\n-- mmHg"
                tvResultPulso.text = "Pulso\n-- bpm"
                showReady(); controller.forceSendCommand("PRESION")
            }
            "TEMPERATURA" -> {
                state.resetTemperature()
                tvResultTemperature.text = "Temperatura\n--"
                showReady(); controller.forceSendCommand("TEMPERATURA")
            }
            "OXIGENO" -> {
                state.resetOxygen()
                tvResultSpO2.text = "SpO2\n-- %"
                tvResultPulseRate.text = "Pulso\n-- bpm"
                tvResultPI.text = "PI\n--"
                waveformView.clear()
                controller.startOxygenMeasurement(delayMs = 5000, timeoutMs = 15000)
            }
            "ECG" -> {
                state.resetEcg()
                layoutResultadosEcg.visibility = View.GONE
                btnStarECG.visibility = View.GONE
                btnRepeat.visibility = View.GONE
                startEcg()
            }
        }
    }

    // ── Manejo de datos entrantes ─────────────────────────

    private fun handleDeviceData(intent: Intent) {
        runOnUiThread {
            when (intent.getStringExtra("type")) {
                "ALTURA_PESO" -> {
                    state.height = intent.getDoubleExtra("height", 0.0)
                    state.weight = intent.getDoubleExtra("weight", 0.0)
                    state.imc = intent.getDoubleExtra("imc", 0.0)
                    if (state.hasHeightWeight()) {
                        tvResultHeight.text = "Altura\n%.1f cm".format(state.height)
                        tvResultWeight.text = "Peso\n%.3f kg".format(state.weight)
                        tvResultIMC.text = "IMC\n%.1f".format(state.imc)
                        if (currentMeasurementType == "ALTURA_PESO") {
                            groupAlturaPeso.visibility = View.VISIBLE
                            if (!completedMeasurements.contains("ALTURA_PESO")) {
                                completedMeasurements.add("ALTURA_PESO")
                                onMeasurementComplete()
                            }
                        }
                    }
                }
                "COMPOSICION" -> {
                    state.fatRate = intent.getDoubleExtra("fat_rate", 0.0)
                    state.waterRate = intent.getDoubleExtra("water_rate", 0.0)
                    state.muscle = intent.getDoubleExtra("muscle", 0.0)
                    state.metabolism = intent.getIntExtra("metabolism", 0)
                    state.visceralFat = intent.getDoubleExtra("visceral_fat", 0.0)
                    state.idealWeight = intent.getDoubleExtra("ideal_weight", 0.0)
                    state.protein = intent.getDoubleExtra("protein", 0.0)
                    state.mineral = intent.getDoubleExtra("mineral", 0.0)
                    state.fatKg = intent.getDoubleExtra("fat", 0.0)
                    state.waterKg = intent.getDoubleExtra("water", 0.0)
                    state.notFat = intent.getDoubleExtra("not_fat", 0.0)
                    state.fatType = intent.getIntExtra("fat_type", 0)

                    if (currentMeasurementType == "COMPOSICION") {
                        // ✅ OCULTAR BARRA DE PROGRESO Y MOSTRAR RESULTADOS AL TERMINAR
                        layoutProgressComposicion.visibility = View.GONE
                        layoutResultadosComposicion.visibility = View.VISIBLE

                        renderComposition()
                        if (!completedMeasurements.contains("COMPOSICION")) {
                            completedMeasurements.add("COMPOSICION")
                            onMeasurementComplete()
                        }
                    }
                }
                "COMPOSICION_PROGRESS" -> {
                    val progress = intent.getIntExtra("progress", 0)
                    if (currentMeasurementType == "COMPOSICION") {
                        layoutProgressComposicion.visibility = View.VISIBLE
                        layoutResultadosComposicion.visibility = View.GONE

                        // ✅ Animación suave de la barra
                        val animator = ValueAnimator.ofInt(pbComposicion.progress, progress)
                        animator.duration = 500
                        animator.addUpdateListener { animation ->
                            val animValue = animation.animatedValue as Int
                            pbComposicion.progress = animValue
                            tvProgressPercent.text = "$animValue%"
                        }
                        animator.start()

                        if (progress < 100) {
                            groupComposicion.visibility = View.VISIBLE
                        }
                    }
                }
                "PRESION" -> {
                    state.systolic = intent.getIntExtra("systolic", 0)
                    state.diastolic = intent.getIntExtra("diastolic", 0)
                    state.pulse = intent.getIntExtra("pulse", 0)
                    if (state.hasPressure()) {
                        tvResultSistolica.text = "Sistólica\n${state.systolic} mmHg"
                        tvResultDiastolica.text = "Diastólica\n${state.diastolic} mmHg"
                        tvResultPulso.text = "Pulso\n${state.pulse} bpm"
                        if (currentMeasurementType == "PRESION") {
                            groupPresion.visibility = View.VISIBLE
                            if (!completedMeasurements.contains("PRESION")) {
                                completedMeasurements.add("PRESION")
                                onMeasurementComplete()
                            }
                        }
                    }
                }
                "TEMPERATURA" -> {
                    state.temperature = intent.getDoubleExtra("temperature", 0.0)
                    state.temperatureF = intent.getDoubleExtra("temperature_f", 0.0)
                    state.bodyMode = intent.getStringExtra("mode") ?: ""
                    if (state.hasTemperature()) {
                        tvResultTemperature.text = "Temperatura\n%.1f °C / %.1f °F".format(state.temperature, state.temperatureF)
                        if (currentMeasurementType == "TEMPERATURA") {
                            groupTemperatura.visibility = View.VISIBLE
                            if (!completedMeasurements.contains("TEMPERATURA")) {
                                completedMeasurements.add("TEMPERATURA")
                                onMeasurementComplete()
                            }
                        }
                    }
                }
                "OXIGENO" -> {
                    state.pendingSpO2 = intent.getIntExtra("spo2", 0)
                    state.pendingPulseRate = intent.getIntExtra("pulse_rate", 0)
                    state.pendingPI = intent.getDoubleExtra("pi", 0.0)
                    if (state.pendingSpO2 > 0) {
                        state.hasPendingResult = true
                        // No mostramos groupOxigeno aquí aún, esperamos a onOxygenResult (confirmado)
                    }
                }
                "STATUS" -> {
                    val status = intent.getStringExtra("data") ?: ""
                    onStatusMessage(status, "#0F3E82")
                }
            }
        }
    }

    private fun renderComposition() {
        tvResultFat.text = "Tasa Grasa\n%.1f%%".format(state.fatRate)
        tvResultWater.text = "Tasa Agua\n%.1f%%".format(state.waterRate)
        tvResultFatKg.text = "Grasa\n%.1f kg".format(state.fatKg)
        tvResultWaterKg.text = "Agua\n%.1f kg".format(state.waterKg)
        tvResultMuscle.text = "Músculo\n%.1f kg".format(state.muscle)
        tvResultNotFat.text = "Masa Libre\n%.1f kg".format(state.notFat)
        tvResultProtein.text = "Proteína\n%.1f kg".format(state.protein)
        tvResultMineral.text = "Minerales\n%.1f kg".format(state.mineral)
        tvResultMetabolism.text = "Metabolismo\n${state.metabolism} kcal"
        tvResultVisceralFat.text = "Grasa Visc.\n%.1f".format(state.visceralFat)
        tvResultIdealWeight.text = "Peso Ideal\n%.1f kg".format(state.idealWeight)
        tvResultFatType.text = "Tipo Grasa\n${state.fatType}"
        groupComposicion.visibility = View.VISIBLE
    }

    private fun showEcgResults(results: EcgResults, image: Bitmap) {
        ecgImageView.setImageBitmap(image)
        ecgImageView.visibility = View.VISIBLE
        cardEcgImage.visibility = View.VISIBLE
        tvResultHeartRate.text = "Frecuencia\n${results.heartRate} bpm"
        tvResultPAxis.text = "Eje P\n${results.pAxis}"
        tvResultQRSAxis.text = "Eje QRS\n${results.qrsAxis}"
        tvResultTAxis.text = "Eje T\n${results.tAxis}"
        tvResultPR.text = "Intervalo PR\n${results.prInterval}"
        tvResultQRS.text = "Duración QRS\n${results.qrsDuration}"
        tvResultQT.text = "Intervalo QT\n${results.qtd}"
        tvResultQTC.text = "QT Corregido\n${results.qtc}"
        tvResultRV5.text = "Onda RV5\n${results.rv5}"
        tvResultSV1.text = "Onda SV1\n${results.sv1}"
        tvResultResCode.text = "Resultado\n${results.resCode}"
        btnStarECG.visibility = View.GONE
        layoutResultadosEcg.visibility = View.VISIBLE
        groupEcg.visibility = View.VISIBLE

        if (!completedMeasurements.contains("ECG")) {
            completedMeasurements.add("ECG")
            onMeasurementComplete()
        }
    }

    // ── UI helpers ────────────────────────────────────────

    private fun switchTab(title: String, key: String, group: LinearLayout?) {
        cancelCountdownTimer()
        controller.cancelTimers()
        setRepeatMode(false)
        listOf(groupAlturaPeso, groupTemperatura, groupComposicion, groupPresion,
            groupOxigeno, groupEcg, groupAzucar, groupAcidoUrico, groupColesterol)
            .forEach { it.visibility = View.GONE }

        // ✅ OCULTAR SIEMPRE RESULTADOS/PROGRESO AL CAMBIAR DE PESTAÑA
        layoutProgressComposicion.visibility = View.GONE
        layoutResultadosComposicion.visibility = View.GONE
        layoutResultadosEcg.visibility = View.GONE

        group?.visibility = View.VISIBLE

        // Cargar últimos resultados para esta pestaña
        loadLastResults(key)

        waveformView.visibility = if (key == "oxigeno") View.VISIBLE else View.GONE
        tvTitle.text = title
        setGuideImage("instruccion_$key")
        updateButtonVisibility()

        // Solo reproducir audio "start" si esta medición AÚN NO ha sido completada
        if (!completedMeasurements.contains(currentMeasurementType)) {
            getAudioName(currentMeasurementType, "start")?.let { playAudio(it) }
        }
    }

    private fun loadLastResults(key: String) {
        // 1. Resetear visibilidad de grupos históricos
        lastGroupAlturaPeso.visibility = View.GONE
        lastGroupPresion.visibility = View.GONE
        lastGroupOxigeno.visibility = View.GONE
        lastGroupComposicion.visibility = View.GONE
        lastGroupEcg.visibility = View.GONE
        tvLastTemperature.visibility = View.GONE

        // 2. Mostrar placeholders inmediatamente según la pestaña
        when (key) {
            "altura_peso" -> {
                tvLastHeight.text = "Altura\n--"
                tvLastWeight.text = "Peso\n--"
                tvLastIMC.text = "IMC\n--"
                lastGroupAlturaPeso.visibility = View.VISIBLE
            }
            "presion" -> {
                tvLastSistolica.text = "Sistólica\n--"
                tvLastDiastolica.text = "Diastólica\n--"
                tvLastPulso.text = "Pulso\n--"
                lastGroupPresion.visibility = View.VISIBLE
            }
            "oxigeno" -> {
                tvLastSpO2.text = "SpO2\n--"
                tvLastPulseRate.text = "Pulso\n--"
                tvLastPI.text = "PI\n--"
                lastGroupOxigeno.visibility = View.VISIBLE
            }
            "temperatura" -> {
                tvLastTemperature.text = "Temperatura: --"
                tvLastTemperature.visibility = View.VISIBLE
            }
            "composicion" -> {
                tvLastFat.text = "Grasa\n--"
                tvLastMuscle.text = "Músculo\n--"
                tvLastMetabolism.text = "Meta.\n--"
                lastGroupComposicion.visibility = View.VISIBLE
            }
            "ecg" -> {
                tvLastEcgHeartRate.text = "Frecuencia\n--"
                tvLastEcgQRS.text = "QRS\n--"
                tvLastEcgResult.text = "Resultado\n--"
                lastGroupEcg.visibility = View.VISIBLE
            }
        }

        // 3. Si hay paciente, buscar resultados reales en la DB
        if (pacienteIdLocal == 0L) return

        Thread {
            runBlocking {
                val db = AppDatabase.getInstance(this@MeasurementActivity)
                val lastResult = db.resultadoDao().obtenerResultadosPorIdLocal(pacienteIdLocal).firstOrNull()

                if (lastResult != null) {
                    runOnUiThread {
                        when (key) {
                            "altura_peso" -> {
                                tvLastHeight.text = "Altura\n${lastResult.altura.ifBlank { "--" }} cm"
                                tvLastWeight.text = "Peso\n${lastResult.peso.ifBlank { "--" }} kg"
                                tvLastIMC.text = "IMC\n${lastResult.imc.ifBlank { "--" }}"
                            }
                            "presion" -> {
                                tvLastSistolica.text = "Sistólica\n${lastResult.sistolica.ifBlank { "--" }} mmHg"
                                tvLastDiastolica.text = "Diastólica\n${lastResult.diastolica.ifBlank { "--" }} mmHg"
                                tvLastPulso.text = "Pulso\n${lastResult.pulso.ifBlank { "--" }} bpm"
                            }
                            "oxigeno" -> {
                                tvLastSpO2.text = "SpO2\n${lastResult.spo2.ifBlank { "--" }} %"
                                tvLastPulseRate.text = "Pulso\n${lastResult.frecuencia_pulso.ifBlank { "--" }} bpm"
                                tvLastPI.text = "PI\n${lastResult.indice_perfusion.ifBlank { "--" }}"
                            }
                            "temperatura" -> {
                                tvLastTemperature.text = "Temperatura: ${lastResult.temperatura.ifBlank { "--" }} °C"
                            }
                            "composicion" -> {
                                tvLastFat.text = "Grasa\n${lastResult.grasa_corporal.ifBlank { "--" }}%"
                                tvLastMuscle.text = "Músculo\n${lastResult.masa_muscular.ifBlank { "--" }} kg"
                                tvLastMetabolism.text = "Meta.\n${lastResult.metabolismo_basal.ifBlank { "--" }} kcal"
                            }
                            "ecg" -> {
                                val hr = if (lastResult.frecuencia_cardiaca.isNotBlank()) "${lastResult.frecuencia_cardiaca} bpm" else "--"
                                val qrs = if (lastResult.duracion_qrs.isNotBlank()) lastResult.duracion_qrs else "--"
                                val res = if (lastResult.resultado_ecg.isNotBlank()) lastResult.resultado_ecg else "--"
                                tvLastEcgHeartRate.text = "Frecuencia\n$hr"
                                tvLastEcgQRS.text = "QRS\n$qrs"
                                tvLastEcgResult.text = "Resultado\n$res"
                            }
                        }
                    }
                }
            }
        }.start()
    }

    private fun setGuideImage(name: String) {
        val id = resources.getIdentifier(name, "drawable", packageName)
        val resId = if (id != 0) id else R.mipmap.ic_launcher
        if (imgGuide.drawable == null) {
            imgGuide.setImageResource(resId)
            return
        }
        // Crossfade suave en vez de un cambio brusco al pasar de una guía a otra.
        imgGuide.animate().alpha(0f).setDuration(150).withEndAction {
            imgGuide.setImageResource(resId)
            imgGuide.animate().alpha(1f).setDuration(200).start()
        }.start()
    }

    /** Pulso lento de escala en la imagen guía, para que no se sienta una foto estática e inerte. */
    private fun startGuideBreathing() {
        if (guideBreathingAnimator != null) return
        val scaleX = android.animation.PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.035f)
        val scaleY = android.animation.PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.035f)
        guideBreathingAnimator = android.animation.ObjectAnimator.ofPropertyValuesHolder(imgGuide, scaleX, scaleY).apply {
            duration = 2800
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            start()
        }
    }

    private fun stopGuideBreathing() {
        guideBreathingAnimator?.cancel()
        guideBreathingAnimator = null
        imgGuide.scaleX = 1f
        imgGuide.scaleY = 1f
    }

    private fun showReady() {
        showStatusMessage("Dispositivo listo para medición", "#4CAF50")
        controller.postStatusDelayed("Esperando resultados...", "#FF9800", 5000)
    }

    private fun showStatusMessage(msg: String, color: String) {
        tvStatusMessage.text = msg
        tvStatusMessage.setTextColor(Color.parseColor(color))
        tvStatusMessage.visibility = View.VISIBLE

        val isWaiting = color == "#FF9800"
        val isSuccess = color == "#4CAF50" && msg.contains("completada")

        if (isWaiting) {
            startStatusShimmer()
        } else {
            stopStatusShimmer()
        }
        showSuccessCheck(isSuccess)
    }

    /** Mientras se esperan resultados: un barrido de color recorre la píldora del mensaje de
     *  estado, y las píldoras de resultado de la vista activa "pulsan" con el color del tema. */
    private fun startStatusShimmer() {
        statusSweep.visibility = View.VISIBLE
        statusSweep.start()
        startPillPulse()
    }

    private fun stopStatusShimmer() {
        statusSweep.stop()
        statusSweep.visibility = View.GONE
        stopPillPulse()
    }

    /** Muestra (con una animación de aparición) o esconde el check verde de medición completada. */
    private fun showSuccessCheck(show: Boolean) {
        if (show) {
            ivStatusCheck.scaleX = 0f
            ivStatusCheck.scaleY = 0f
            ivStatusCheck.visibility = View.VISIBLE
            ivStatusCheck.animate()
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(350)
                .setInterpolator(OvershootInterpolator())
                .start()
        } else {
            ivStatusCheck.visibility = View.GONE
        }
    }

    private fun updateButtonVisibility() {
        when (currentMeasurementType) {
            "ECG" -> {
                btnStarECG.visibility = if (state.ecgResults == null) View.VISIBLE else View.GONE
                btnRepeat.visibility = if (state.ecgResults != null) View.VISIBLE else View.GONE
            }
            "ALTURA_PESO" -> {
                btnStarECG.visibility = View.GONE
                btnRepeat.visibility = if (state.hasHeightWeight()) View.VISIBLE else View.GONE
            }
            "COMPOSICION" -> {
                btnStarECG.visibility = View.GONE
                btnRepeat.visibility = if (state.fatRate > 0) View.VISIBLE else View.GONE
            }
            "PRESION" -> {
                btnStarECG.visibility = View.GONE
                btnRepeat.visibility = if (state.hasPressure()) View.VISIBLE else View.GONE
            }
            "TEMPERATURA" -> {
                btnStarECG.visibility = View.GONE
                btnRepeat.visibility = if (state.hasTemperature()) View.VISIBLE else View.GONE
            }
            "OXIGENO" -> {
                btnStarECG.visibility = View.GONE
                btnRepeat.visibility = if (state.hasOxygen()) View.VISIBLE else View.GONE
            }
            else -> {
                btnStarECG.visibility = View.GONE
                btnRepeat.visibility = View.GONE
            }
        }
    }

    private fun updateDeviceStatusMessage() {
        if (isFinishing || isDestroyed) return
        if (!deviceManager.isAvailableFor(currentMeasurementType)) {
            showStatusMessage("Error: no se encuentra el dispositivo", "#F44336")
            setRepeatMode(true)
        }
    }

    private fun findFirstActiveButtonAndClick() {
        allButtons.firstOrNull { it.visibility == View.VISIBLE }?.performClick()
    }

    private fun checkLastMeasurement() {
        val prefs = getSharedPreferences("DevicePrefs", Context.MODE_PRIVATE)
        val incomplete = listOf(
            "device_altura_peso" to state.hasHeightWeight(),
            "device_composicion" to (state.fatRate > 0),
            "device_presion" to state.hasPressure(),
            "device_temperatura" to state.hasTemperature(),
            "device_oxigeno" to state.hasOxygen(),
            "device_ecg" to state.hasEcg()
        ).any { (k, ok) -> prefs.getBoolean(k, true) && !ok }

        if (!incomplete) btnConfirmMeasurements.visibility = View.VISIBLE
    }

    private fun sendPatientDataToService() {
        LocalBroadcastManager.getInstance(this).sendBroadcast(
            Intent("SET_PATIENT_DATA")
                .putExtra("birth_date", pacienteFechaNacimiento)
                .putExtra("gender", pacienteGenero)
        )
    }

    private fun showFullScreenImage(image: Bitmap) {
        val dialog = android.app.Dialog(this)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_fullscreen_image)
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout(
            android.view.WindowManager.LayoutParams.MATCH_PARENT,
            android.view.WindowManager.LayoutParams.MATCH_PARENT
        )
        dialog.findViewById<ImageView>(R.id.fullscreenImageView).apply {
            setImageBitmap(image)
            setOnClickListener { dialog.dismiss() }
        }
        dialog.findViewById<ImageView>(R.id.btnCloseFullscreen).setOnClickListener { dialog.dismiss() }
        if (!isFinishing && !isDestroyed) dialog.show()
    }

    // ── Persistencia ──────────────────────────────────────

    private fun saveAllResults() {
        val editor = getSharedPreferences("ResultsPrefs", Context.MODE_PRIVATE).edit()
        with(state) {
            if (height > 0) {
                editor.putFloat("height", height.toFloat())
                editor.putFloat("weight", weight.toFloat())
                editor.putFloat("imc", imc.toFloat())
            }
            if (fatRate > 0) editor.putFloat("fat_rate", fatRate.toFloat())
            if (waterRate > 0) editor.putFloat("water_rate", waterRate.toFloat())
            if (muscle > 0) editor.putFloat("muscle", muscle.toFloat())
            if (metabolism > 0) editor.putInt("metabolism", metabolism)
            if (visceralFat > 0) editor.putFloat("visceral_fat", visceralFat.toFloat())
            if (idealWeight > 0) editor.putFloat("ideal_weight", idealWeight.toFloat())
            if (protein > 0) editor.putFloat("protein", protein.toFloat())
            if (mineral > 0) editor.putFloat("mineral", mineral.toFloat())
            if (fatKg > 0) editor.putFloat("fat", fatKg.toFloat())
            if (waterKg > 0) editor.putFloat("water", waterKg.toFloat())
            if (notFat > 0) editor.putFloat("not_fat", notFat.toFloat())
            if (fatType > 0) editor.putInt("fat_type", fatType)
            if (systolic > 0) {
                editor.putInt("systolic", systolic)
                editor.putInt("diastolic", diastolic)
                editor.putInt("pulse", pulse)
            }
            if (temperature > 0) {
                editor.putFloat("temperature", temperature.toFloat())
                editor.putFloat("temperature_f", temperatureF.toFloat())
            }
            if (spo2 > 0) {
                editor.putInt("spo2", spo2)
                editor.putInt("pulse_rate", pulseRate)
                editor.putFloat("pi", pi.toFloat())
            }
        }
        editor.apply()

        state.ecgResults?.let {
            // ✅ saveToPrefs corre en el hilo del botón, no en UI
            ecgManager.saveToPrefs(this, it, state.ecgImage)
        }
    }

    private fun guardarResultadosEnBD() {
        val db = AppDatabase.getInstance(this)
        val dao = db.resultadoDao()

        val fecha = java.text.SimpleDateFormat("dd/MM/yyyy HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date())

        val rutaEcgInterna: String = ecgManager.getLastJpgPath()?.let { src ->
            EcgStorage.copyToInternal(this, src) ?: ""
        } ?: ""

        // ✅ Leer el paciente DIRECTO de Room para obtener el id_usuario_web real
        val pacienteActual = runBlocking {
            if (pacienteIdLocal != 0L) {
                db.pacienteDao().obtenerPacientePorIdLocal(pacienteIdLocal)
            } else null
        }

        val idUsuarioWebFinal = pacienteActual?.id_usuario_web ?: 0
        Log.d(TAG, "💾 Guardando resultado: id_local=$pacienteIdLocal, id_usuario_web=$idUsuarioWebFinal")

        val r = Resultado(
            id_local = pacienteIdLocal,
            id_usuario_web = idUsuarioWebFinal,   // ✅ Este es el valor correcto
            altura = if (state.height > 0) "%.1f".format(state.height) else "",
            peso = if (state.weight > 0) "%.3f".format(state.weight) else "",
            imc = if (state.imc > 0) "%.1f".format(state.imc) else "",
            grasa_corporal = if (state.fatRate > 0) "%.1f".format(state.fatRate) else "",
            grasa_corporal_kg = if (state.fatKg > 0) "%.1f".format(state.fatKg) else "",
            agua_corporal = if (state.waterRate > 0) "%.1f".format(state.waterRate) else "",
            agua_corporal_kg = if (state.waterKg > 0) "%.1f".format(state.waterKg) else "",
            masa_muscular = if (state.muscle > 0) "%.1f".format(state.muscle) else "",
            masa_libre_grasa = if (state.notFat > 0) "%.1f".format(state.notFat) else "",
            proteina = if (state.protein > 0) "%.1f".format(state.protein) else "",
            minerales = if (state.mineral > 0) "%.1f".format(state.mineral) else "",
            metabolismo_basal = if (state.metabolism > 0) state.metabolism.toString() else "",
            grasa_visceral = if (state.visceralFat > 0) "%.1f".format(state.visceralFat) else "",
            peso_ideal = if (state.idealWeight > 0) "%.1f".format(state.idealWeight) else "",
            tipo_grasa = if (state.fatType > 0) state.fatType.toString() else "",
            sistolica = if (state.systolic > 0) state.systolic.toString() else "",
            diastolica = if (state.diastolic > 0) state.diastolic.toString() else "",
            pulso = if (state.pulse > 0) state.pulse.toString() else "",
            temperatura = if (state.temperature > 0) "%.1f".format(state.temperature) else "",
            temperatura_f = if (state.temperatureF > 0) "%.1f".format(state.temperatureF) else "",
            spo2 = if (state.spo2 > 0) state.spo2.toString() else "",
            frecuencia_pulso = if (state.pulseRate > 0) state.pulseRate.toString() else "",
            indice_perfusion = if (state.pi > 0) "%.1f".format(state.pi) else "",
            frecuencia_cardiaca = state.ecgResults?.heartRate?.toString() ?: "",
            eje_p = state.ecgResults?.pAxis ?: "",
            eje_qrs = state.ecgResults?.qrsAxis ?: "",
            eje_t = state.ecgResults?.tAxis ?: "",
            intervalo_pr = state.ecgResults?.prInterval ?: "",
            duracion_qrs = state.ecgResults?.qrsDuration ?: "",
            intervalo_qt = state.ecgResults?.qtd ?: "",
            qt_corregido = state.ecgResults?.qtc ?: "",
            onda_rv5 = state.ecgResults?.rv5 ?: "",
            onda_sv1 = state.ecgResults?.sv1 ?: "",
            resultado_ecg = state.ecgResults?.resCode ?: "",
            ruta_ecg = rutaEcgInterna,
            fecha_medicion = fecha
        )

        Thread { runBlocking { dao.insertarResultado(r) } }.start()
    }

    // ── Callbacks del Controller ──────────────────────────

    override fun onStatusMessage(message: String, color: String) {
        showStatusMessage(message, color)
        // El controlador reporta los fallos ("no se encuentra el dispositivo", "Error de medición...")
        // solo como mensaje en rojo: en ese caso también se ofrece "Reintentar" arriba.
        if (color.equals(ERROR_COLOR, ignoreCase = true)) {
            runOnUiThread {
                if (!isFinishing && !isDestroyed) setRepeatMode(true)
            }
        }
    }

    override fun onMeasurementComplete() {
        runOnUiThread {
            if (!isActivityResumed || isFinishing || isDestroyed) return@runOnUiThread
            showStatusMessage("Medición completada correctamente", "#4CAF50")
            setRepeatMode(false)
            updateButtonVisibility()
            checkLastMeasurement()
            getAudioName(currentMeasurementType, "exito")?.let { playAudio(it, forceRestart = true) }
            startCountdownToNextTab()
        }
    }

    /**
     * El botón superior derecho es "Repetir" cuando ya hay resultado y "Reintentar" cuando la
     * medición falló (sin resultado no había forma de volver a intentarla desde la barra superior).
     */
    private fun setRepeatMode(error: Boolean) {
        btnRepeat.text = if (error) "Reintentar" else "Repetir"
        if (error) btnRepeat.visibility = View.VISIBLE
    }

    override fun onMeasurementError() {
        runOnUiThread {
            if (!isActivityResumed || isFinishing || isDestroyed) return@runOnUiThread
            cancelCountdownTimer()
            layoutProgressComposicion.visibility = View.GONE
            layoutResultadosComposicion.visibility = View.GONE
            layoutResultadosEcg.visibility = View.GONE
            showStatusMessage("Error de medición, favor de repetir", "#F44336")
            updateButtonVisibility()
            setRepeatMode(true)
            getAudioName(currentMeasurementType, "error")?.let { playAudio(it, forceRestart = true) }
        }
    }

    private fun getAudioPrefix(type: String): String? {
        return when (type) {
            "ALTURA_PESO" -> "alturapeso"
            "COMPOSICION" -> "comp"
            "PRESION" -> "presion"
            "TEMPERATURA" -> "temp"
            "OXIGENO" -> "oxigeno"
            "ECG" -> "ecg"
            else -> null
        }
    }

    private fun getAudioName(type: String, event: String): String? {
        val prefix = getAudioPrefix(type) ?: return null
        if (type == "ECG" && event == "exito") {
            val resIdExito = resources.getIdentifier("ecg_exito", "raw", packageName)
            if (resIdExito != 0) return "ecg_exito"
            val resIdS = resources.getIdentifier("ecg_s", "raw", packageName)
            if (resIdS != 0) return "ecg_s"
        }
        return "${prefix}_$event"
    }

    private fun playAudio(soundName: String, forceRestart: Boolean = false) {
        if (!isActivityResumed || isFinishing || isDestroyed) {
            Log.d(TAG, "🔇 Ignorando reproducción de audio $soundName porque la actividad no está en primer plano")
            return
        }
        if (!forceRestart && mediaPlayer?.isPlaying == true && currentPlayingAudioName == soundName) {
            return
        }
        stopAudio()
        try {
            val resId = resources.getIdentifier(soundName, "raw", packageName)
            if (resId != 0) {
                currentPlayingAudioName = soundName
                mediaPlayer = MediaPlayer.create(this, resId)?.apply {
                    setOnCompletionListener {
                        currentPlayingAudioName = null
                        stopAudio()
                    }
                    start()
                }
            } else {
                Log.w(TAG, "Audio resource not found: $soundName")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error playing audio: $soundName", e)
        }
    }

    private fun stopAudio() {
        try {
            currentPlayingAudioName = null
            mediaPlayer?.let {
                if (it.isPlaying) {
                    it.stop()
                }
                it.release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping audio", e)
        } finally {
            mediaPlayer = null
        }
    }

    private fun isButtonForCurrentType(button: View, type: String): Boolean {
        return when (type) {
            "ALTURA_PESO" -> button.id == R.id.btnNavAlturaPeso
            "COMPOSICION" -> button.id == R.id.btnNavComposicion
            "PRESION" -> button.id == R.id.btnNavPresion
            "TEMPERATURA" -> button.id == R.id.btnNavTemperatura
            "OXIGENO" -> button.id == R.id.btnNavOxigeno
            "ECG" -> button.id == R.id.btnNavEcg
            "AZUCAR" -> button.id == R.id.btnNavAzucar
            "ACIDO_URICO" -> button.id == R.id.btnNavAcidoUrico
            "COLESTEROL" -> button.id == R.id.btnNavColesterol
            else -> false
        }
    }

    private fun getNextVisibleButton(): View? {
        val visibleButtons = allButtons.filter { it.visibility == View.VISIBLE }
        val currentIndex = visibleButtons.indexOfFirst { isButtonForCurrentType(it, currentMeasurementType) }
        if (currentIndex != -1 && currentIndex + 1 < visibleButtons.size) {
            return visibleButtons[currentIndex + 1]
        }
        return null
    }

    private fun startCountdownToNextTab() {
        cancelCountdownTimer()

        val nextButton = getNextVisibleButton() ?: return

        tvCountdownMessage.text = "Siguiente medición en 5 segundos"
        tvCountdownMessage.visibility = View.VISIBLE

        countdownTimer = object : CountDownTimer(5000, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val secondsLeft = (millisUntilFinished / 1000).toInt() + 1
                if (secondsLeft in 1..5) {
                    val unitText = if (secondsLeft == 1) "segundo" else "segundos"
                    tvCountdownMessage.text = "Siguiente medición en $secondsLeft $unitText"
                }
            }

            override fun onFinish() {
                cancelCountdownTimer()
                nextButton.performClick()
            }
        }.start()
    }

    private fun cancelCountdownTimer() {
        countdownTimer?.cancel()
        countdownTimer = null
        if (::tvCountdownMessage.isInitialized) {
            tvCountdownMessage.visibility = View.GONE
            tvCountdownMessage.text = ""
        }
    }

    override fun onButtonVisibilityChanged() = updateButtonVisibility()

    override fun onOxygenResult(spo2: Int, pulseRate: Int, pi: Double) {
        state.spo2 = spo2
        state.pulseRate = pulseRate
        state.pi = pi
        tvResultSpO2.text = "SpO2\n$spo2 %"
        tvResultPulseRate.text = "Pulso\n$pulseRate bpm"
        tvResultPI.text = "PI\n%.1f".format(pi)

        if (currentMeasurementType == "OXIGENO") {
            groupOxigeno.visibility = View.VISIBLE
            if (!completedMeasurements.contains("OXIGENO")) {
                completedMeasurements.add("OXIGENO")
                onMeasurementComplete()
            }
        }
    }

    // ── Lifecycle ─────────────────────────────────────────
    override fun onResume() {
        super.onResume()
        isActivityResumed = true
        ecgManager.onResume()
        updateButtonVisibility()
        checkLastMeasurement()
        if (VideoLoopRemote.isMeasurementMuteEnabled(this)) {
            lifecycleScope.launch {
                val ok = VideoLoopRemote.setMuted(this@MeasurementActivity, true)
                if (!ok) {
                    Log.w(TAG, "No se pudo silenciar el video en " +
                            VideoLoopRemote.getSavedHostPort(this@MeasurementActivity))
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        isActivityResumed = false
        cancelCountdownTimer()
        stopAudio()
        controller.cancelTimers()
        stopStatusShimmer()
        stopGuideBreathing()
    }

    override fun onStop() {
        super.onStop()
        isActivityResumed = false
        cancelCountdownTimer()
        stopAudio()
        controller.cancelTimers()
    }

    override fun onDestroy() {
        super.onDestroy()
        isActivityResumed = false
        cancelCountdownTimer()
        stopAudio()
        ecgManager.cancel()
        controller.destroy()
        // Si se sale a mitad de una composición, detenerla: si no, la báscula quedaba en modo
        // composición y las siguientes mediciones de altura/peso no llegaban a la pantalla.
        if (currentMeasurementType == "COMPOSICION" && !completedMeasurements.contains("COMPOSICION")) {
            LocalBroadcastManager.getInstance(this)
                .sendBroadcast(Intent("SEND_STOP_COMMAND").putExtra("command_type", "COMPOSICION"))
        }
        deviceManager.unregister()
        try { LocalBroadcastManager.getInstance(this).unregisterReceiver(dataReceiver) } catch (_: Exception) {}
        try { LocalBroadcastManager.getInstance(this).unregisterReceiver(waveReceiver) } catch (_: Exception) {}
        if (VideoLoopRemote.isMeasurementMuteEnabled(this)) {
            val appContext = applicationContext
            CoroutineScope(Dispatchers.IO).launch {
                val ok = VideoLoopRemote.setMuted(appContext, false)
                if (!ok) {
                    Log.w(TAG, "No se pudo reactivar el audio en " +
                            VideoLoopRemote.getSavedHostPort(appContext))
                }
            }
        }
    }
}