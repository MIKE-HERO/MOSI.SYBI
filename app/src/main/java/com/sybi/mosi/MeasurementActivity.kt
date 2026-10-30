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
import android.os.Handler
import android.os.Looper
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
        private const val KEY_CURRENT_MEASUREMENT_TYPE = "current_measurement_type"
        private const val KEY_ECG_ESPERANDO_RESULTADO = "ecg_esperando_resultado"
        private const val KEY_ECG_MEASUREMENT_START_TIME = "ecg_measurement_start_time"
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
    private lateinit var measurementResults: MeasurementResults

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
    private lateinit var ecgLoadingView: View
    private lateinit var ecgLoadingAnim: com.sybi.mosi.measurement.EqualizerLoaderView
    private var ecgEsperandoResultado = false
    private var ecgMeasurementStartTime = 0L

    private lateinit var imgGuide: ImageView
    private lateinit var tvGuideStep: TextView
    private lateinit var guidePanel: View
    private lateinit var measuringAnimationHost: View
    private lateinit var measuringAnimationView: com.sybi.mosi.measurement.EqualizerLoaderView
    private lateinit var resultsPanel: View
    private val guideHandler = Handler(Looper.getMainLooper())
    private var guideSequenceToken = 0
    private var guideBreathingAnimator: android.animation.ObjectAnimator? = null
    private lateinit var waveformView: WaveformView
    private lateinit var layoutStatusContent: LinearLayout
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

    private lateinit var tvStatusProgress: TextView
    private lateinit var layoutResultadosComposicion: LinearLayout

    private lateinit var btnStarECG: Button
    private lateinit var btnConfirmMeasurements: View
    private lateinit var btnRepeat: View
    private lateinit var tvCountdownMessage: TextView
    private lateinit var tvMeasurementTitle: TextView // NUEVO
    private var countdownTimer: CountDownTimer? = null
    private var mediaPlayer: MediaPlayer? = null

    private lateinit var allButtons: List<View>
    private lateinit var tabSelectorIndicator: View
    private var tabIconActiveColor: Int = Color.parseColor("#B8D4E8")
    private var currentMeasurementType = ""
    private val completedMeasurements = mutableSetOf<String>()
    private val erroredMeasurements = mutableSetOf<String>()
    private var currentPlayingAudioName: String? = null
    private var isActivityResumed = false
    private var oxygenReadingStarted = false

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
                if (!oxygenReadingStarted) {
                    oxygenReadingStarted = true
                    showStatusMessage("Esperando resultados...", "#FF9800")
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)
        setContentView(R.layout.activity_measurement)

        if (savedInstanceState == null) {
            getSharedPreferences("ResultsPrefs", Context.MODE_PRIVATE).edit().clear().apply()
        }

        runCatching { startService(Intent(this, SerialService::class.java)) }

        readPatientIntent()
        bindViews()
        setupManagers()
        setupReceivers()
        setupButtons()
        applyTheme()
        startGuideBreathing()
        restoreEcgPendingState(savedInstanceState)

        controller.postDelayed({
            deviceManager.query()
        }, 300)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_CURRENT_MEASUREMENT_TYPE, currentMeasurementType)
        outState.putBoolean(KEY_ECG_ESPERANDO_RESULTADO, ecgEsperandoResultado)
        outState.putLong(KEY_ECG_MEASUREMENT_START_TIME, ecgMeasurementStartTime)
    }

    /**
     * En equipos de poca RAM, Android puede matar esta Activity en segundo plano
     * mientras la app externa de ECG está al frente. Al volver, el proceso se
     * recrea desde cero: sin esto, se perdería el aviso de "procesando..." y la
     * búsqueda del archivo de resultado nunca se reengancharía, dejando la
     * medición colgada para siempre.
     */
    private fun restoreEcgPendingState(savedInstanceState: Bundle?) {
        val savedState = savedInstanceState ?: return
        if (savedState.getString(KEY_CURRENT_MEASUREMENT_TYPE) != "ECG") return

        // Evita que el auto-clic del primer botón disponible (pensado solo para el
        // arranque inicial) pise la pestaña de ECG que estamos restaurando.
        firstButtonTriggered = true

        showEcgTab()

        val estabaEsperando = savedState.getBoolean(KEY_ECG_ESPERANDO_RESULTADO, false)
        val startTime = savedState.getLong(KEY_ECG_MEASUREMENT_START_TIME, 0L)
        if (estabaEsperando && startTime > 0L) {
            ecgEsperandoResultado = true
            ecgMeasurementStartTime = startTime
            btnStarECG.visibility = View.GONE
            ecgLoadingView.visibility = View.VISIBLE
            ecgManager.resumePendingMeasurement(pacienteId.toString(), startTime)
        }
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
        measurementResults = MeasurementResults(this)
        measurementResults.bindViews()

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
        ecgLoadingView = findViewById(R.id.ecgLoadingView)
        ecgLoadingAnim = findViewById(R.id.ecgLoadingAnim)
        imgGuide = findViewById(R.id.imgMeasurementGuide)
        tvGuideStep = findViewById(R.id.tvGuideStep)
        guidePanel = findViewById(R.id.guidePanel)
        measuringAnimationHost = findViewById(R.id.measuringAnimationHost)
        measuringAnimationView = findViewById(R.id.measuringAnimationView)
        resultsPanel = findViewById(R.id.resultsPanel)
        waveformView = findViewById(R.id.waveformView)
        layoutStatusContent = findViewById(R.id.layoutStatusContent)
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

        tvStatusProgress = findViewById(R.id.tvStatusProgress)
        layoutResultadosComposicion = findViewById(R.id.layoutResultadosComposicion)

        btnStarECG = findViewById(R.id.btnStarECG)
        btnConfirmMeasurements = findViewById(R.id.btnConfirmMeasurements)
        btnRepeat = findViewById(R.id.btnRepeatReading)
        tvCountdownMessage = findViewById(R.id.tvCountdownMessage)
        tvMeasurementTitle = findViewById(R.id.tvMeasurementTitle) // NUEVO
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

    private val colorReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val newColor = intent.getStringExtra("new_color") ?: return
            applyTheme(Color.parseColor(newColor))
        }
    }

    private fun setupReceivers() {
        LocalBroadcastManager.getInstance(this).apply {
            registerReceiver(dataReceiver, IntentFilter("DEVICE_DATA_RECEIVED"))
            registerReceiver(waveReceiver, IntentFilter("OXYGEN_WAVE_DATA"))
            registerReceiver(colorReceiver, IntentFilter("ACTION_UPDATE_THEME"))
        }
    }

    private fun applyTheme() {
        val saved = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
            .getString("BackgroundColor", "#0F3E82") ?: "#0F3E82"
        applyTheme(Color.parseColor(saved))
    }

    /** Repinta la pantalla con [color] — llamado al abrir la pantalla y cada vez que cambia
     *  el color de marca en vivo (ajuste manual o ciclo del modo multicolor). */
    private fun applyTheme(color: Int) {
        findViewById<View>(R.id.bottomMeasurementBarContainer).setBackgroundColor(color)
        btnStarECG.backgroundTintList = android.content.res.ColorStateList.valueOf(color)
        applyPillTheme(color)

        // Tono claro del propio color de marca para resaltar el ícono de la pestaña activa.
        tabIconActiveColor = lightenColor(color, 0.65f)
        if (::allButtons.isInitialized) actualizarTabSeleccionado(animar = false)
    }

    /** Encapsula los valores de resultado y el mensaje de estado en píldoras con un tono claro
     *  alusivo al color del tema (logo/menú), en todas las vistas de medición, y prepara los
     *  colores del barrido de carga y del pulso de las píldoras en espera. */
    private fun applyPillTheme(themeColor: Int) {
        pillLightColor = lightenColor(themeColor, 0.85f)
        pillPulseColor = lightenColor(themeColor, 0.55f)

        layoutStatusContent.background = pillDrawable(pillLightColor, 28f)
        statusSweep.setCornerRadiusDp(28f)
        statusSweep.setSweepColor(themeColor)

        measurementResults.applyLoaderColor(themeColor)
        measuringAnimationView.setBarColor(themeColor)
        ecgLoadingAnim.setBarColor(themeColor)
        waveformView.setLineColor(themeColor)
    }

    private fun getPatientAge(): Int {
        if (pacienteFechaNacimiento.isBlank()) return 30
        return try {
            val partes = pacienteFechaNacimiento.split("/")
            if (partes.size < 3) return 30
            val dia = partes[0].toInt()
            val mes = partes[1].toInt()
            val anio = partes[2].toInt()

            val calendario = java.util.Calendar.getInstance()
            val anioActual = calendario.get(java.util.Calendar.YEAR)
            val mesActual = calendario.get(java.util.Calendar.MONTH) + 1
            val diaActual = calendario.get(java.util.Calendar.DAY_OF_MONTH)

            var edad = anioActual - anio
            if (mesActual < mes || (mesActual == mes && diaActual < dia)) {
                edad--
            }
            edad.coerceIn(1, 120)
        } catch (e: Exception) {
            30
        }
    }

    private fun updatePillColor(textView: TextView, isNormal: Boolean) {
        val color = if (isNormal) {
            Color.parseColor("#4D4CAF50") // Verde semitransparente (~30% opacity Green)
        } else {
            Color.parseColor("#4DF44336") // Rojo semitransparente (~30% opacity Red)
        }
        textView.background = pillDrawable(color, 18f)
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
        tabSelectorIndicator = findViewById(R.id.tabSelectorIndicator)

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
        btnStarECG.setOnClickListener { startEcg(ecgDerivacionesConfiguradas()) }
        btnAzucar.setOnClickListener { showUnavailable("Azúcar en Sangre", "azucar") }
        btnAcidoUrico.setOnClickListener { showUnavailable("Ácido Úrico", "acido_urico") }
        btnColesterol.setOnClickListener { showUnavailable("Colesterol Total", "colesterol") }
        btnRepeat.setOnClickListener { repeatCurrent() }

        findViewById<View>(R.id.btnExitMeasurement).setOnClickListener {
            if (hayMedicionesTomadas()) mostrarDialogoSalirConInforme() else finish()
        }
        btnConfirmMeasurements.setOnClickListener {
            btnConfirmMeasurements.isEnabled = false
            findViewById<TextView>(R.id.tvConfirmMeasurementsText)?.text = "Guardando..."

            Thread {
                // Todo el trabajo pesado en background
                guardarResultadosEnBD()
                saveAllResults()

                runOnUiThread {
                    startActivity(Intent(this, ResultsActivity::class.java)
                        .putExtra("id_local", pacienteIdLocal))
                    finish()
                }
            }.start()
        }
    }

    /** ¿El paciente alcanzó a tomar al menos una medición en esta sesión? */
    private fun hayMedicionesTomadas(): Boolean =
        state.hasHeightWeight() || state.hasComposition() || state.hasPressure() ||
                state.hasTemperature() || state.hasOxygen() || state.hasEcg() ||
                completedMeasurements.isNotEmpty() || erroredMeasurements.isNotEmpty()

    /** Al salir con mediciones tomadas: ofrecer ver el informe antes de irse. Si acepta, se
     *  guardan en el historial (igual que al confirmar) y se abre el informe; si no, sale. */
    private fun mostrarDialogoSalirConInforme() {
        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle("Mediciones tomadas")
            .setMessage("¿Deseas ver el informe de tus mediciones antes de salir?")
            .setPositiveButton("Ver informe") { _, _ ->
                Thread {
                    guardarResultadosEnBD()
                    saveAllResults()
                    runOnUiThread {
                        startActivity(
                            Intent(this, ResultsActivity::class.java)
                                .putExtra("id_local", pacienteIdLocal)
                        )
                        finish()
                    }
                }.start()
            }
            .setNegativeButton("Salir sin ver") { _, _ -> finish() }
            .create()
        setupDialogKeyboardBehavior(dialog)
        dialog.show()
    }

    // ── Flujos de medición ────────────────────────────────

    private fun startHeightWeight() {
        currentMeasurementType = "ALTURA_PESO"
        switchTab("Altura y Peso", "altura_peso", groupAlturaPeso, skipGenericGuide = true)
        measurementResults.updateHeightWeight(state.height, state.weight, state.imc)

        if (completedMeasurements.contains("ALTURA_PESO") || state.hasHeightWeight()) {
            showHeightWeightStage(3)
            setHeightWeightPresentation(showResults = true)
            btnRepeat.visibility = View.VISIBLE
            showStatusMessage("Medición completada correctamente", "#4CAF50")
            showSuccessCheck(true)
            return
        }
        setHeightWeightPresentation(showResults = false)
        startHeightWeightGuideSequence()
        showReady()
        controller.sendCommand("ALTURA_PESO")
    }

    private fun startComposition() {
        currentMeasurementType = "COMPOSICION"
        switchTab("Composición Corporal", "composicion", groupComposicion, skipGenericGuide = true)

        if (completedMeasurements.contains("COMPOSICION") || state.hasComposition()) {
            setGuideImage("composicion_sujetando")
            setHeightWeightPresentation(showResults = true)
            tvStatusProgress.visibility = View.GONE
            renderComposition()
            btnRepeat.visibility = View.VISIBLE
            showStatusMessage("Medición completada correctamente", "#4CAF50")
            showSuccessCheck(true)
            return
        }
        if (!state.hasHeightWeight()) {
            Toast.makeText(this, "⚠️ Primero mide altura y peso", Toast.LENGTH_LONG).show()
            onMeasurementError(); return
        }

        measurementResults.clearComposition()
        setHeightWeightPresentation(showResults = false)
        startCompositionGuideSequence()
        tvStatusProgress.text = "0%"
        tvStatusProgress.visibility = View.VISIBLE
        ivStatusCheck.visibility = View.GONE

        sendPatientDataToService()
        showReady()
        controller.sendCommand("COMPOSICION")
    }

    private fun startPressure() {
        currentMeasurementType = "PRESION"
        switchTab("Presión Arterial", "presion", groupPresion, skipGenericGuide = true)
        measurementResults.updatePressure(state.systolic, state.diastolic, state.pulse)

        if (completedMeasurements.contains("PRESION") || state.hasPressure()) {
            setGuideImage("guia_presion")
            setHeightWeightPresentation(showResults = true)
            btnRepeat.visibility = View.VISIBLE
            showStatusMessage("Medición completada correctamente", "#4CAF50")
            showSuccessCheck(true)
            return
        }
        setHeightWeightPresentation(showResults = false)
        startClinicalGuideSequence(
            type = "PRESION",
            imageName = "guia_presion",
            steps = listOf(
                "1. Siéntate con la espalda apoyada y coloca ambos pies sobre la plataforma.",
                "2. Introduce el brazo en el brazalete y apóyalo sin hacer fuerza.",
                "3. Presiona el botón azul, no hables y permanece inmóvil."
            )
        )
        showReady()
        controller.sendCommand("PRESION")
    }

    private fun startTemperature() {
        currentMeasurementType = "TEMPERATURA"
        switchTab("Temperatura Corporal", "temperatura", groupTemperatura, skipGenericGuide = true)
        measurementResults.updateTemperature(state.temperature, state.temperatureF)

        if (completedMeasurements.contains("TEMPERATURA") || state.hasTemperature()) {
            setGuideImage("guia_temperatura")
            setHeightWeightPresentation(showResults = true)
            btnRepeat.visibility = View.VISIBLE
            showStatusMessage("Medición completada correctamente", "#4CAF50")
            showSuccessCheck(true)
            return
        }
        setHeightWeightPresentation(showResults = false)
        startClinicalGuideSequence(
            type = "TEMPERATURA",
            imageName = "guia_temperatura",
            steps = listOf(
                "1. Toma el termómetro por el mango.",
                "2. Acércalo al centro de la frente sin tocar la piel.",
                "3. Mantén la posición un instante mientras se registra la temperatura."
            ),
            stepDurationMs = 1700L
        )
        showReady()
        controller.sendCommand("TEMPERATURA")
    }

    private fun startOxygen() {
        currentMeasurementType = "OXIGENO"
        oxygenReadingStarted = false
        switchTab("Oxígeno en Sangre", "oxigeno", groupOxigeno, skipGenericGuide = true)
        measurementResults.updateOxygen(state.spo2, state.pulseRate, state.pi)

        if (completedMeasurements.contains("OXIGENO") || state.hasOxygen()) {
            setGuideImage("guia_oximetro")
            setHeightWeightPresentation(showResults = true)
            btnRepeat.visibility = View.VISIBLE
            showStatusMessage("Medición completada correctamente", "#4CAF50")
            showSuccessCheck(true)
            return
        }
        setHeightWeightPresentation(showResults = false)
        startClinicalGuideSequence(
            type = "OXIGENO",
            imageName = "guia_oximetro",
            steps = listOf(
                "1. Siéntate cómodamente y relaja la mano.",
                "2. Introduce completamente el dedo índice en el oxímetro.",
                "3. Mantén la mano quieta hasta que termine la lectura."
            )
        )
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
            showStatusMessage("Medición completada correctamente", "#4CAF50")
            showSuccessCheck(true)
        } else {
            showStatusMessage("Presione el botón ECG para iniciar la medición", "#FF9800")
            showSuccessCheck(false)
            btnStarECG.visibility = if (ecgEsperandoResultado) View.GONE else View.VISIBLE
            ecgLoadingView.visibility = if (ecgEsperandoResultado) View.VISIBLE else View.GONE
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

    /** Derivaciones configuradas en "Parámetros de medición" (ver MeasurementParametersActivity). */
    private fun ecgDerivacionesConfiguradas(): Int =
        getSharedPreferences("DevicePrefs", Context.MODE_PRIVATE)
            .getInt(TestElementsActivity.PREF_ECG_DERIVACIONES, 6)

    private fun startEcg(derivaciones: Int) {
        btnStarECG.visibility = View.GONE
        val startTime = System.currentTimeMillis()
        val ok = ecgManager.startMeasurement(
            pacienteNombre, pacienteId.toString(), pacienteGenero, pacienteFechaNacimiento, derivaciones
        )
        if (ok) {
            ecgEsperandoResultado = true
            ecgMeasurementStartTime = startTime
            ecgLoadingView.visibility = View.VISIBLE
        } else {
            Toast.makeText(this, "No se pudo encontrar la app de ECG", Toast.LENGTH_LONG).show()
            onMeasurementError()
        }
    }

    private fun repeatCurrent() {
        cancelCountdownTimer()
        setRepeatMode(false)
        showSuccessCheck(false)
        completedMeasurements.remove(currentMeasurementType)
        erroredMeasurements.remove(currentMeasurementType)
        getAudioName(currentMeasurementType, "start")?.let { playAudio(it, forceRestart = true) }
        if (!deviceManager.isAvailableFor(currentMeasurementType)) {
            showStatusMessage("Error: no se encuentra el dispositivo", "#F44336")
            setRepeatMode(true)
            return
        }
        when (currentMeasurementType) {
            "ALTURA_PESO" -> {
                setHeightWeightPresentation(showResults = false)
                state.resetHeightWeight()
                measurementResults.updateHeightWeight(0.0, 0.0, 0.0)
                showReady(); controller.forceSendCommand("ALTURA_PESO")
            }
            "COMPOSICION" -> {
                if (!state.hasHeightWeight()) {
                    Toast.makeText(this, "⚠️ Primero mide altura y peso", Toast.LENGTH_LONG).show()
                    return
                }
                state.resetComposition()
                measurementResults.clearComposition()
                setHeightWeightPresentation(showResults = false)
                startCompositionGuideSequence()
                tvStatusProgress.text = "0%"
                tvStatusProgress.visibility = View.VISIBLE
                ivStatusCheck.visibility = View.GONE

                sendPatientDataToService(); showReady(); controller.forceSendCommand("COMPOSICION")
            }
            "PRESION" -> {
                setHeightWeightPresentation(showResults = false)
                startClinicalGuideSequence("PRESION", "guia_presion", listOf(
                    "1. Siéntate con la espalda apoyada y coloca ambos pies sobre la plataforma.",
                    "2. Introduce el brazo en el brazalete y apóyalo sin hacer fuerza.",
                    "3. Presiona el botón azul, no hables y permanece inmóvil."
                ))
                state.resetPressure()
                measurementResults.updatePressure(0, 0, 0)
                showReady(); controller.forceSendCommand("PRESION")
            }
            "TEMPERATURA" -> {
                setHeightWeightPresentation(showResults = false)
                startClinicalGuideSequence("TEMPERATURA", "guia_temperatura", listOf(
                    "1. Toma el termómetro por el mango.",
                    "2. Acércalo al centro de la frente sin tocar la piel.",
                    "3. Mantén la posición un instante mientras se registra la temperatura."
                ), 1700L)
                state.resetTemperature()
                measurementResults.updateTemperature(0.0, 0.0)
                showReady(); controller.forceSendCommand("TEMPERATURA")
            }
            "OXIGENO" -> {
                setHeightWeightPresentation(showResults = false)
                startClinicalGuideSequence("OXIGENO", "guia_oximetro", listOf(
                    "1. Siéntate cómodamente y relaja la mano.",
                    "2. Introduce completamente el dedo índice en el oxímetro.",
                    "3. Mantén la mano quieta hasta que termine la lectura."
                ))
                state.resetOxygen()
                oxygenReadingStarted = false
                measurementResults.updateOxygen(0, 0, 0.0)
                waveformView.clear()
                controller.startOxygenMeasurement()
            }
            "ECG" -> {
                state.resetEcg()
                layoutResultadosEcg.visibility = View.GONE
                btnStarECG.visibility = View.GONE
                btnRepeat.visibility = View.GONE
                startEcg(ecgDerivacionesConfiguradas())
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
                        measurementResults.updateHeightWeight(state.height, state.weight, state.imc)
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
                        tvStatusProgress.visibility = View.GONE
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
                        tvStatusProgress.text = "$progress%"
                        tvStatusProgress.visibility = View.VISIBLE
                        ivStatusCheck.visibility = View.GONE
                        showStatusMessage("Analizando composición...", "#FF9800")
                        groupComposicion.visibility = View.VISIBLE
                    }
                }
                "PRESION" -> {
                    state.systolic = intent.getIntExtra("systolic", 0)
                    state.diastolic = intent.getIntExtra("diastolic", 0)
                    state.pulse = intent.getIntExtra("pulse", 0)
                    if (state.hasPressure()) {
                        measurementResults.updatePressure(state.systolic, state.diastolic, state.pulse)
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
                        measurementResults.updateTemperature(state.temperature, state.temperatureF)
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
        val age = getPatientAge()
        val isMale = pacienteGenero.equals("M", ignoreCase = true)
        measurementResults.updateComposition(state, isMale, age)
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

        val isMale = pacienteGenero.equals("M", ignoreCase = true)
        val maxQtc = if (isMale) 440.0 else 450.0
        val prVal = results.prInterval.toIntOrNull() ?: 0
        val qrsVal = results.qrsDuration.toIntOrNull() ?: 0
        val qtcVal = results.qtc.toDoubleOrNull() ?: 0.0

        updatePillColor(tvResultHeartRate, results.heartRate in 60..100)
        updatePillColor(tvResultPAxis, true)
        updatePillColor(tvResultQRSAxis, true)
        updatePillColor(tvResultTAxis, true)
        updatePillColor(tvResultPR, prVal in 120..200)
        updatePillColor(tvResultQRS, qrsVal in 60..110)
        updatePillColor(tvResultQT, true)
        updatePillColor(tvResultQTC, qtcVal in 300.0..maxQtc)
        updatePillColor(tvResultRV5, true)
        updatePillColor(tvResultSV1, true)
        updatePillColor(tvResultResCode, results.resCode.equals("Normal", ignoreCase = true) || results.resCode == "0")
        ecgEsperandoResultado = false
        ecgMeasurementStartTime = 0L
        ecgLoadingView.visibility = View.GONE
        btnStarECG.visibility = View.GONE
        layoutResultadosEcg.visibility = View.VISIBLE
        groupEcg.visibility = View.VISIBLE

        if (!completedMeasurements.contains("ECG")) {
            completedMeasurements.add("ECG")
            onMeasurementComplete()
        }
    }

    // ── UI helpers ────────────────────────────────────────

    private fun switchTab(
        title: String,
        key: String,
        group: LinearLayout?,
        skipGenericGuide: Boolean = false
    ) {
        guideSequenceToken++
        cancelCountdownTimer()
        controller.cancelTimers()
        controller.prepareTab(key.uppercase())
        if (currentMeasurementType == "OXIGENO" && key.uppercase() != "OXIGENO") {
            LocalBroadcastManager.getInstance(this).sendBroadcast(Intent("STOP_OXYGEN_MEASURE"))
        }
        setRepeatMode(false)
        tvStatusProgress.visibility = View.GONE
        // Por si se cambia de pestaña a medio "esperando resultado": no dejar la
        // animación grande de la pestaña anterior atascada sobre la guía nueva.
        mostrarAnimacionMidiendo(false)
        listOf(groupAlturaPeso, groupTemperatura, groupComposicion, groupPresion,
            groupOxigeno, groupEcg, groupAzucar, groupAcidoUrico, groupColesterol)
            .forEach { it.visibility = View.GONE }

        layoutResultadosEcg.visibility = View.GONE
        guidePanel.visibility = View.VISIBLE
        resultsPanel.visibility = View.VISIBLE

        group?.visibility = View.VISIBLE

        // Cargar últimos resultados para esta pestaña
        loadLastResults(key)

        waveformView.visibility = if (key == "oxigeno") View.VISIBLE else View.GONE

        // --- CAMBIO: Actualizar título central ---
        tvMeasurementTitle.text = title
        tvMeasurementTitle.visibility = View.VISIBLE
        tvCountdownMessage.visibility = View.GONE
        // -----------------------------------------

        // Los tipos con su propia secuencia de guía (altura/peso, composición,
        // presión, temperatura, oxígeno) la ponen justo después de esta llamada;
        // si aquí también se pusiera la imagen genérica "instruccion_$key", se
        // vería un parpadeo: la genérica aparece un instante y luego se
        // reemplaza por la guía real específica.
        if (!skipGenericGuide) {
            setGuideImage("instruccion_$key")
        }
        tvGuideStep.visibility = if (key == "altura_peso") View.VISIBLE else View.GONE
        updateButtonVisibility()

        // Solo reproducir audio "start" si esta medición AÚN NO ha sido completada
        if (!completedMeasurements.contains(currentMeasurementType)) {
            getAudioName(currentMeasurementType, "start")?.let { playAudio(it) }
        }

        actualizarTabSeleccionado()
    }

    /**
     * La guía (izquierda) y los resultados (derecha) permanecen visibles todo el
     * tiempo, en paralelo; lo que cubre/descubre los datos reales mientras se
     * espera es únicamente el overlay de [mostrarAnimacionMidiendo], no estos
     * paneles. `showResults` ya no oculta ningún panel.
     */
    private fun setHeightWeightPresentation(showResults: Boolean) {
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

    private fun startHeightWeightGuideSequence() {
        val token = ++guideSequenceToken
        showHeightWeightStage(1)
        guideHandler.postDelayed({
            if (token == guideSequenceToken && currentMeasurementType == "ALTURA_PESO" && !state.hasHeightWeight()) {
                showHeightWeightStage(2)
            }
        }, 2200)
        guideHandler.postDelayed({
            if (token == guideSequenceToken && currentMeasurementType == "ALTURA_PESO" && !state.hasHeightWeight()) {
                showHeightWeightStage(3)
                startAnalysisMessages(token, "ALTURA_PESO")
            }
        }, 4800)
    }

    private fun startCompositionGuideSequence() {
        val token = ++guideSequenceToken
        showCompositionStage(1)
        guideHandler.postDelayed({
            if (token == guideSequenceToken && currentMeasurementType == "COMPOSICION" && !state.hasComposition()) {
                showCompositionStage(2)
            }
        }, 2600)
        guideHandler.postDelayed({
            if (token == guideSequenceToken && currentMeasurementType == "COMPOSICION" && !state.hasComposition()) {
                showCompositionStage(3)
                startAnalysisMessages(token, "COMPOSICION")
            }
        }, 5400)
    }

    private fun showCompositionStage(stage: Int) {
        setGuideImage("composicion_sujetando")
        tvGuideStep.text = when (stage) {
            1 -> "1. Siéntate con la espalda recta y coloca ambos pies sobre la plataforma."
            2 -> "2. Sujeta firmemente los electrodos con ambas manos, como se muestra en la imagen."
            else -> "3. Mantén los brazos estables, no hables y permanece inmóvil."
        }
        tvGuideStep.visibility = View.VISIBLE
    }

    private fun startClinicalGuideSequence(
        type: String,
        imageName: String,
        steps: List<String>,
        stepDurationMs: Long = 2400L
    ) {
        val token = ++guideSequenceToken
        setGuideImage(imageName)
        tvGuideStep.text = steps.first()
        tvGuideStep.visibility = View.VISIBLE
        steps.drop(1).forEachIndexed { index, message ->
            guideHandler.postDelayed({
                if (token == guideSequenceToken && currentMeasurementType == type && isMeasurementWaiting(type)) {
                    tvGuideStep.text = message
                }
            }, stepDurationMs * (index + 1))
        }
        guideHandler.postDelayed({
            if (token == guideSequenceToken && currentMeasurementType == type && isMeasurementWaiting(type)) {
                startAnalysisMessages(token, type)
            }
        }, stepDurationMs * steps.size)
    }

    private fun isMeasurementWaiting(type: String): Boolean = when (type) {
        "ALTURA_PESO" -> !state.hasHeightWeight()
        "COMPOSICION" -> !state.hasComposition()
        "PRESION" -> !state.hasPressure()
        "TEMPERATURA" -> !state.hasTemperature()
        "OXIGENO" -> !state.hasOxygen()
        else -> false
    }

    private fun startAnalysisMessages(token: Int, type: String) {
        val messages = listOf(
            "Un momento, estamos analizando tus datos…",
            "Validando la calidad de la medición…",
            "Calculando tus resultados…",
            "Casi terminamos, permanece inmóvil…"
        )
        fun showMessage(index: Int) {
            val waiting = isMeasurementWaiting(type)
            if (token != guideSequenceToken || currentMeasurementType != type || !waiting) return
            tvGuideStep.animate().alpha(0f).setDuration(160).withEndAction {
                if (token != guideSequenceToken) return@withEndAction
                tvGuideStep.text = messages[index % messages.size]
                tvGuideStep.animate().alpha(1f).setDuration(220).start()
            }.start()
            guideHandler.postDelayed({ showMessage(index + 1) }, 2300)
        }
        guideHandler.postDelayed({ showMessage(0) }, 2200)
    }

    private fun showHeightWeightStage(stage: Int) {
        when (stage) {
            1 -> {
                setGuideImage("altura_peso_sentado")
                tvGuideStep.text = "1. Siéntate frente a la máquina y coloca ambos pies sobre la plataforma."
            }
            2 -> {
                setGuideImage("altura_peso_levantando")
                tvGuideStep.text = "2. Levántate despacio y mantén los pies dentro del área marcada."
            }
            else -> {
                setGuideImage("altura_peso_de_pie")
                tvGuideStep.text = if (state.hasHeightWeight()) {
                    "¡Listo! Revisa tus resultados y elige Siguiente o Regresar."
                } else {
                    "3. Mira al frente, mantén la espalda recta y permanece inmóvil."
                }
            }
        }
        tvGuideStep.visibility = View.VISIBLE
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

        // "Dispositivo listo para medición" (verde) también cuenta como "esperando": es el
        // hueco de ~5s antes de que arranque el "Esperando resultados..." (naranja) donde,
        // sin esto, se veían los campos de resultado en blanco. Este mensaje nunca se usa
        // para Oxígeno ni ECG, así que no afecta sus propios flujos.
        val isWaiting = color == "#FF9800" || msg == "Dispositivo listo para medición"
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
        mostrarAnimacionMidiendo(true)
    }

    private fun stopStatusShimmer() {
        statusSweep.stop()
        statusSweep.visibility = View.GONE
        stopPillPulse()
        mostrarAnimacionMidiendo(false)
    }

    /**
     * Mitad derecha de la pantalla: mientras se espera el resultado del sensor,
     * cubre el panel de resultados con una animación grande ("midiendo"). Al
     * terminar (con éxito o error) se descubre y aparecen los datos reales. La
     * guía de postura (mitad izquierda) permanece visible todo el tiempo, sin
     * animaciones de "esperando" mezcladas en las tarjetas de resultado.
     */
    private fun mostrarAnimacionMidiendo(midiendo: Boolean) {
        // Oxígeno (gráfica de pulsaciones en tiempo real) y Composición (barra de
        // porcentaje) ya muestran su propio indicador de progreso específico —
        // taparlos con el loader genérico ocultaría información más útil que la
        // propia animación genérica. ECG tampoco lo usa: la medición la realiza
        // la app externa, así que aquí solo se necesita ver el botón "Comenzar
        // medición" (u, obtenido el resultado, la imagen del trazo).
        val tieneIndicadorPropio = currentMeasurementType == "OXIGENO" || currentMeasurementType == "COMPOSICION" ||
            currentMeasurementType == "ECG"
        measuringAnimationHost.visibility = if (midiendo && !tieneIndicadorPropio) View.VISIBLE else View.GONE
    }

    /** Muestra (con una animación de aparición) o esconde el check verde de medición completada. */
    private fun showSuccessCheck(show: Boolean) {
        if (show) {
            tvStatusProgress.visibility = View.GONE
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
                btnStarECG.visibility = if (state.ecgResults == null && !ecgEsperandoResultado) View.VISIBLE else View.GONE
                ecgLoadingView.visibility = if (ecgEsperandoResultado) View.VISIBLE else View.GONE
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

        // Dispositivos/mediciones activas según DevicePrefs
        val activeKeys = listOf(
            "device_altura_peso" to "ALTURA_PESO",
            "device_composicion" to "COMPOSICION",
            "device_presion" to "PRESION",
            "device_temperatura" to "TEMPERATURA",
            "device_oxigeno" to "OXIGENO",
            "device_ecg" to "ECG"
        ).filter { (prefKey, _) -> prefs.getBoolean(prefKey, true) }

        // Verificar si todas las mediciones activas fueron intentadas (completadas o con error)
        val allAttempted = activeKeys.isNotEmpty() && activeKeys.all { (_, type) ->
            completedMeasurements.contains(type) || erroredMeasurements.contains(type)
        }

        // O si la última medición activa configurada ya finalizó (éxito o error)
        val lastActiveType = activeKeys.lastOrNull()?.second
        val lastFinished = lastActiveType != null && (
            completedMeasurements.contains(lastActiveType) || erroredMeasurements.contains(lastActiveType)
        )

        if (allAttempted || lastFinished) {
            btnConfirmMeasurements.visibility = View.VISIBLE
        }
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
        val prefsObj = getSharedPreferences("ResultsPrefs", Context.MODE_PRIVATE)
        prefsObj.edit().clear().apply()
        val editor = prefsObj.edit()
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

        val idUsuarioWebFinal = pacienteActual?.id_usuario_web ?: pacienteId
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

        // Insert síncrono: esta función siempre corre dentro de un Thread de fondo (al
        // confirmar y al salir con informe), así garantizamos que el registro ya esté en BD
        // antes de abrir la pantalla siguiente (el informe lo lee de la BD).
        runBlocking { dao.insertarResultado(r) }
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
            controller.cancelStatusDelayed()
            showStatusMessage(getString(R.string.measurement_success), "#4CAF50")
            if (currentMeasurementType == "ALTURA_PESO") {
                guideSequenceToken++
                showHeightWeightStage(3)
                setHeightWeightPresentation(showResults = true)
            } else if (currentMeasurementType == "COMPOSICION") {
                guideSequenceToken++
                setHeightWeightPresentation(showResults = true)
            } else if (currentMeasurementType in setOf("PRESION", "TEMPERATURA", "OXIGENO")) {
                guideSequenceToken++
                setHeightWeightPresentation(showResults = true)
            }
            erroredMeasurements.remove(currentMeasurementType)
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
        val textRetry = if (error) getString(R.string.measurement_retry) else "Repetir"
        findViewById<TextView>(R.id.tvRepeatReadingText)?.text = textRetry
        if (error) btnRepeat.visibility = View.VISIBLE
    }

    override fun onMeasurementError() {
        runOnUiThread {
            if (!isActivityResumed || isFinishing || isDestroyed) return@runOnUiThread
            controller.cancelStatusDelayed()
            cancelCountdownTimer()
            tvStatusProgress.visibility = View.GONE
            if (currentMeasurementType == "ECG") {
                ecgEsperandoResultado = false
                ecgMeasurementStartTime = 0L
                ecgLoadingView.visibility = View.GONE
            }
            if (currentMeasurementType in setOf("ALTURA_PESO", "COMPOSICION", "PRESION", "TEMPERATURA", "OXIGENO")) {
                guideSequenceToken++
                setHeightWeightPresentation(showResults = true)
                // No dejamos ocho tarjetas con loaders activos detrás de un error.
                // El estado y Reintentar permanecen visibles en una superficie limpia.
                if (currentMeasurementType == "COMPOSICION") groupComposicion.visibility = View.GONE
            }
            layoutResultadosEcg.visibility = View.GONE
            showStatusMessage(getString(R.string.measurement_error), "#F44336")
            updateButtonVisibility()
            setRepeatMode(true)
            if (!completedMeasurements.contains(currentMeasurementType)) {
                erroredMeasurements.add(currentMeasurementType)
            }
            checkLastMeasurement()
            getAudioName(currentMeasurementType, "error")?.let { playAudio(it, forceRestart = true) }
            startCountdownToNextTab()
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
        return "${prefix}_$event"
    }

    private fun resolveAudioResId(soundName: String): Int {
        // Android resuelve automáticamente desde app/src/main/res/raw-en/ cuando el idioma es inglés,
        // o desde app/src/main/res/raw/ por defecto (español).
        return resources.getIdentifier(soundName, "raw", packageName)
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
            val resId = resolveAudioResId(soundName)
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

    /**
     * Resalta en la barra inferior la pestaña de [currentMeasurementType]: desliza la "lupa"
     * rectangular (tabSelectorIndicator) hasta esa pestaña y aclara el tono de su ícono;
     * las demás pestañas quedan con su ícono en blanco normal.
     */
    private fun actualizarTabSeleccionado(animar: Boolean = true) {
        val boton = allButtons.firstOrNull { isButtonForCurrentType(it, currentMeasurementType) } ?: return
        allButtons.forEach { btn ->
            val icono = (btn as? LinearLayout)?.getChildAt(0) as? ImageView ?: return@forEach
            icono.setColorFilter(
                if (btn === boton) tabIconActiveColor else Color.WHITE,
                android.graphics.PorterDuff.Mode.SRC_IN
            )
        }
        moverSelectorTab(boton, animar)
    }

    private fun moverSelectorTab(target: View, animar: Boolean) {
        val fila = target.parent as? View
        if (target.width == 0 || fila == null || fila.height == 0) {
            // Aún no se ha hecho layout (p. ej. primera pestaña al abrir la pantalla).
            target.post { moverSelectorTab(target, animar) }
            return
        }
        val margenH = dp(6)
        val destX = (target.left + margenH).toFloat()
        val destW = target.width - margenH * 2
        // Alto completo de la barra de color inferior
        val destY = fila.top.toFloat()
        val destH = fila.height

        val params = tabSelectorIndicator.layoutParams
        if (!animar || tabSelectorIndicator.width == 0) {
            params.width = destW
            params.height = destH
            tabSelectorIndicator.layoutParams = params
            tabSelectorIndicator.x = destX
            tabSelectorIndicator.y = destY
            return
        }

        tabSelectorIndicator.animate()
            .x(destX)
            .y(destY)
            .setDuration(260)
            .setInterpolator(OvershootInterpolator(1.1f))
            .start()

        val anchoInicial = tabSelectorIndicator.width
        val altoInicial = tabSelectorIndicator.height
        if (anchoInicial != destW || altoInicial != destH) {
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 260
                addUpdateListener {
                    val f = it.animatedValue as Float
                    params.width = (anchoInicial + (destW - anchoInicial) * f).toInt()
                    params.height = (altoInicial + (destH - altoInicial) * f).toInt()
                    tabSelectorIndicator.layoutParams = params
                }
                start()
            }
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

        // --- CAMBIO: Ocultar título y mostrar cuenta regresiva ---
        tvMeasurementTitle.visibility = View.GONE
        tvCountdownMessage.visibility = View.VISIBLE
        // ---------------------------------------------------------

        tvCountdownMessage.text = "Siguiente medición en 5 segundos"

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
        measurementResults.updateOxygen(spo2, pulseRate, pi)

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
        applyTheme()
        ecgManager.onResume()
        updateButtonVisibility()
        checkLastMeasurement()
        if (VideoLoopRemote.isMeasurementMuteEnabled(this)) {
            // Solo se silencia si el usuario tenía VideoLoop con sonido; si lo silenció, se respeta
            val appContext = applicationContext
            CoroutineScope(Dispatchers.IO).launch {
                VideoLoopRemote.silenciarMientras(appContext, TAG)
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
        try { LocalBroadcastManager.getInstance(this).unregisterReceiver(colorReceiver) } catch (_: Exception) {}
        if (VideoLoopRemote.isMeasurementMuteEnabled(this)) {
            val appContext = applicationContext
            // Devuelve el audio solo si lo silenció la app; si el usuario lo había silenciado, sigue así
            CoroutineScope(Dispatchers.IO).launch {
                VideoLoopRemote.restaurarSilencio(appContext, TAG)
            }
        }
    }
}