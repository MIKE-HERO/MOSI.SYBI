package com.sybi.mosi

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.view.TextureView
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.sybi.mosi.helpers.Camera2Helper
import com.sybi.mosi.helpers.FaceBiometricsCache
import com.sybi.mosi.helpers.FaceBiometricsHelper
import com.sybi.mosi.helpers.MediaPipeFaceHelper
import kotlinx.coroutines.runBlocking

class FaceLoginActivity : BaseActivity() {

    private lateinit var textureView: TextureView
    private lateinit var btnBackFaceLogin: View
    private lateinit var tvStatus: TextView
    private lateinit var sideBar: View

    private var currentColor: String = "#0F3E82"
    private var camera2Helper: Camera2Helper? = null
    private var capturedBitmap: Bitmap? = null

    @Volatile private var isProcessing = false
    @Volatile private var isRunning = false

    private val handler = Handler(Looper.getMainLooper())
    private val frameIntervalMs = 500L

    // ============================================================
    // UMBRALES de similitud coseno para el pipeline de 3 frames:
    // Frame 1: Descartar < 50% (conserva >= 50%)
    // Frame 2: Descartar < 75% (conserva >= 75%)
    // Frame 3: Seleccionar único/mejor >= 85%
    // ============================================================
    private val UMBRAL_FRAME1_DESCARTE = 0.50f
    private val UMBRAL_FRAME2_DESCARTE = 0.75f
    private val UMBRAL_FRAME3_ACEPTAR = 0.85f

    // Estado del filtro progresivo entre frames
    @Volatile private var currentFrameStage = 1
    @Volatile private var currentCandidates: List<FaceBiometricsCache.PacienteBiometria> = emptyList()

    // ============================================================
    // Control de estado del mensaje (anti-parpadeo)
    // ============================================================
    private enum class UiState {
        NO_FACE,           // No hay rostro
        BAD_QUALITY,       // Rostro detectado pero mala calidad
        PROCESSING,        // Rostro OK, verificando
        NOT_RECOGNIZED,    // Match bajo
        WELCOME            // Login exitoso
    }

    private var currentUiState: UiState = UiState.NO_FACE
    private var lastUiStateChangeMs: Long = 0L
    private val MIN_STATE_DURATION_MS = 1500L   // mínimo 1.5s por estado

    private val frameRunnable = object : Runnable {
        override fun run() {
            if (!isRunning) return
            if (!isProcessing) {
                captureAndAnalyzeFrame()
            }
            handler.postDelayed(this, frameIntervalMs)
        }
    }

    companion object {
        private const val CAMERA_PERMISSION_CODE = 100
        private const val TAG = "FaceLoginActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_face_login)

        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)

        textureView = findViewById(R.id.textureView)
        btnBackFaceLogin = findViewById(R.id.btnBackFaceLogin)
        tvStatus = findViewById(R.id.tvFaceLoginStatus)
        sideBar = findViewById(R.id.sideBarLayout)

        FaceBiometricsHelper.init(this)

        btnBackFaceLogin.setOnClickListener { finish() }

        val colorReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val newColor = intent.getStringExtra("new_color")
                if (newColor != null) {
                    currentColor = newColor
                    applyColorTheme(newColor)
                }
            }
        }
        LocalBroadcastManager.getInstance(this)
            .registerReceiver(colorReceiver, IntentFilter("ACTION_UPDATE_THEME"))

        val prefs = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        val savedColor = prefs.getString("BackgroundColor", "#0F3E82") ?: "#0F3E82"
        currentColor = savedColor
        applyColorTheme(savedColor)

        checkAndPreloadBiometrics()

        if (hasCameraPermission()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.CAMERA),
                CAMERA_PERMISSION_CODE
            )
        }
    }

    private fun applyColorTheme(colorHex: String) {
        val color = Color.parseColor(colorHex)
        sideBar.setBackgroundColor(color)
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
    }

    private fun checkAndPreloadBiometrics() {
        if (!FaceBiometricsCache.isLoaded()) {
            FaceBiometricsCache.preload(this) {
                runOnUiThread {
                    if (currentCandidates.isEmpty() && currentFrameStage == 1) {
                        resetFramePipeline()
                    }
                }
            }
        } else {
            resetFramePipeline()
        }
    }

    private fun resetFramePipeline() {
        currentFrameStage = 1
        currentCandidates = FaceBiometricsCache.getCachedCandidates()
    }

    private fun startCamera() {
        camera2Helper?.stopCamera()
        textureView.scaleX = -1f
        camera2Helper = Camera2Helper(this, textureView).also { it.startCamera() }

        isRunning = true
        currentUiState = UiState.NO_FACE
        lastUiStateChangeMs = 0L

        checkAndPreloadBiometrics()

        handler.postDelayed(frameRunnable, frameIntervalMs)
    }

    /**
     * Actualiza el mensaje de estado solo si:
     *  - El estado lógico cambió, Y
     *  - Ha pasado al menos MIN_STATE_DURATION_MS desde el último cambio.
     *
     * EXCEPCIÓN: los estados PROCESSING y WELCOME se aplican de inmediato
     * (no queremos retrasar "Verificando..." ni "Bienvenido").
     */
    private fun setUiState(newState: UiState, text: String, colorHex: String) {
        val now = System.currentTimeMillis()

        // Los estados urgentes siempre se aplican
        val isUrgent = newState == UiState.WELCOME || newState == UiState.PROCESSING
        val sameState = newState == currentUiState

        // Si es el mismo estado, no hacemos nada (evita re-setear el mismo texto)
        if (sameState) return

        // Si no ha pasado el tiempo mínimo, ignoramos el cambio (anti-parpadeo)
        if (!isUrgent && (now - lastUiStateChangeMs) < MIN_STATE_DURATION_MS) return

        currentUiState = newState
        lastUiStateChangeMs = now

        runOnUiThread {
            // Solo actualiza si el texto realmente cambia (evita redraws innecesarios)
            if (tvStatus.text.toString() != text) {
                tvStatus.text = text
            }
            val color = Color.parseColor(colorHex)
            if (tvStatus.currentTextColor != color) {
                tvStatus.setTextColor(color)
            }
        }
    }

    private fun captureAndAnalyzeFrame() {
        if (isProcessing) return
        if (!FaceBiometricsCache.isLoaded() || FaceBiometricsCache.isPreloading()) {
            setUiState(
                UiState.PROCESSING,
                "Cargando datos de pacientes...",
                "#FF9800"
            )
            return
        }
        val bitmap = camera2Helper?.takePhoto() ?: return
        if (bitmap.width == 0 || bitmap.height == 0) return

        val mirroredBitmap = mirrorBitmap(bitmap) // Espejar para coincidir con el registro

        isProcessing = true

        Thread {
            try {
                val mpResult = MediaPipeFaceHelper.detect(mirroredBitmap)
                val hasFace = mpResult != null && mpResult.faceLandmarks().isNotEmpty()

                if (hasFace && mpResult != null) {
                    val landmarks = mpResult.faceLandmarks().first()

                    // === Control de calidad ANTES de procesar ===
                    if (!FaceBiometricsHelper.isFaceQualityGood(landmarks, mirroredBitmap)) {
                        setUiState(
                            UiState.BAD_QUALITY,
                            "Acerque el rostro y mire de frente",
                            "#FF9800"
                        )
                        resetFramePipeline()
                        isProcessing = false
                        return@Thread
                    }

                    val liveBiometrics = FaceBiometricsHelper.processFace(mirroredBitmap, mpResult)

                    if (liveBiometrics != null) {
                        setUiState(
                            UiState.PROCESSING,
                            "Verificando identidad...",
                            "#FF9800"
                        )
                        processFaceLogin(liveBiometrics)
                    } else {
                        setUiState(
                            UiState.BAD_QUALITY,
                            "Mire fijamente a la cámara",
                            "#4CAF50"
                        )
                        resetFramePipeline()
                        isProcessing = false
                    }
                } else {
                    setUiState(
                        UiState.NO_FACE,
                        "Coloque su rostro frente a la cámara",
                        "#4CAF50"
                    )
                    resetFramePipeline()
                    isProcessing = false
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Error procesando frame: ${e.message}", e)
                resetFramePipeline()
                isProcessing = false
            }
        }.start()
    }

    private fun processFaceLogin(liveBiometrics: FaceBiometricsHelper.BiometricFaceData) {
        Thread {
            try {
                if (!FaceBiometricsCache.isLoaded()) {
                    checkAndPreloadBiometrics()
                    setUiState(
                        UiState.PROCESSING,
                        "Cargando datos de pacientes...",
                        "#FF9800"
                    )
                    isProcessing = false
                    return@Thread
                }

                if (currentCandidates.isEmpty()) {
                    resetFramePipeline()
                }

                val candidatesToEvaluate = currentCandidates
                val stage = currentFrameStage

                Log.d(TAG, "=== FRAME STAGE $stage: Evaluando ${candidatesToEvaluate.size} candidatos ===")

                when (stage) {
                    1 -> {
                        // Frame 1: Comparar con todos y descartar < 50%
                        val pasaronStage1 = mutableListOf<FaceBiometricsCache.PacienteBiometria>()
                        for (candidato in candidatesToEvaluate) {
                            val similitud = FaceBiometricsHelper.matchFaces(
                                liveBiometrics, candidato.biometria
                            )
                            Log.d(TAG, "[Frame 1] ${candidato.paciente.nombre} - Similitud: $similitud")
                            if (similitud >= UMBRAL_FRAME1_DESCARTE) { // >= 0.50f (50%)
                                pasaronStage1.add(candidato)
                            }
                        }

                        Log.d(TAG, "[Frame 1] Pasaron ${pasaronStage1.size} de ${candidatesToEvaluate.size} candidatos (>= 50%)")

                        if (pasaronStage1.isEmpty()) {
                            setUiState(
                                UiState.NOT_RECOGNIZED,
                                "Rostro no reconocido. Intente nuevamente",
                                "#F44336"
                            )
                            resetFramePipeline()
                        } else {
                            currentCandidates = pasaronStage1
                            currentFrameStage = 2
                            setUiState(
                                UiState.PROCESSING,
                                "Verificando... (Paso 1/3)",
                                "#FF9800"
                            )
                        }
                        isProcessing = false
                    }

                    2 -> {
                        // Frame 2: Comparar SOLO con usuarios con > 50% y descartar < 75%
                        val pasaronStage2 = mutableListOf<FaceBiometricsCache.PacienteBiometria>()
                        for (candidato in candidatesToEvaluate) {
                            val similitud = FaceBiometricsHelper.matchFaces(
                                liveBiometrics, candidato.biometria
                            )
                            Log.d(TAG, "[Frame 2] ${candidato.paciente.nombre} - Similitud: $similitud")
                            if (similitud >= UMBRAL_FRAME2_DESCARTE) { // >= 0.75f (75%)
                                pasaronStage2.add(candidato)
                            }
                        }

                        Log.d(TAG, "[Frame 2] Pasaron ${pasaronStage2.size} de ${candidatesToEvaluate.size} candidatos (>= 75%)")

                        if (pasaronStage2.isEmpty()) {
                            setUiState(
                                UiState.NOT_RECOGNIZED,
                                "Rostro no reconocido. Intente nuevamente",
                                "#F44336"
                            )
                            resetFramePipeline()
                        } else {
                            currentCandidates = pasaronStage2
                            currentFrameStage = 3
                            setUiState(
                                UiState.PROCESSING,
                                "Verificando... (Paso 2/3)",
                                "#FF9800"
                            )
                        }
                        isProcessing = false
                    }

                    3 -> {
                        // Frame 3: Comparar SOLO con usuarios con > 75% y seleccionar al unico/mejor con > 85%
                        var mejorCandidato: FaceBiometricsCache.PacienteBiometria? = null
                        var mayorSimilitud = -1f

                        for (candidato in candidatesToEvaluate) {
                            val similitud = FaceBiometricsHelper.matchFaces(
                                liveBiometrics, candidato.biometria
                            )
                            Log.d(TAG, "[Frame 3] ${candidato.paciente.nombre} - Similitud: $similitud")
                            if (similitud >= UMBRAL_FRAME3_ACEPTAR && similitud > mayorSimilitud) { // >= 0.85f (85%)
                                mayorSimilitud = similitud
                                mejorCandidato = candidato
                            }
                        }

                        if (mejorCandidato != null) {
                            val paciente = mejorCandidato.paciente
                            Log.d(TAG, "[Frame 3] MATCH FINAL: ${paciente.nombre} con sim $mayorSimilitud")

                            isRunning = false
                            handler.removeCallbacks(frameRunnable)

                            setUiState(
                                UiState.WELCOME,
                                "¡Bienvenido ${paciente.nombre}!",
                                "#4CAF50"
                            )

                            runOnUiThread {
                                Toast.makeText(
                                    this@FaceLoginActivity,
                                    "¡Bienvenido ${paciente.nombre}!",
                                    Toast.LENGTH_LONG
                                ).show()

                                val intent = Intent(this@FaceLoginActivity, ProfileActivity::class.java)
                                intent.putExtra("id_local", paciente.id_local)
                                intent.putExtra("nombre", paciente.nombre)
                                intent.putExtra("apellido_paterno", paciente.apellido_paterno)
                                intent.putExtra("apellido_materno", paciente.apellido_materno)
                                intent.putExtra("fecha_nacimiento", paciente.fecha_nacimiento)
                                intent.putExtra("genero", paciente.genero)
                                intent.putExtra("curp", paciente.curp)
                                intent.putExtra("telefono", paciente.telefono)
                                intent.putExtra("correo", paciente.correo)
                                intent.putExtra("direccion", paciente.direccion)

                                val sessionType = this@FaceLoginActivity.intent
                                    .getStringExtra("session_type") ?: "measurement"
                                intent.putExtra("session_type", sessionType)

                                startActivity(intent)
                                finish()
                            }
                        } else {
                            Log.d(TAG, "[Frame 3] Ningún candidato alcanzó el 85% final")
                            setUiState(
                                UiState.NOT_RECOGNIZED,
                                "Rostro no reconocido. Intente nuevamente",
                                "#F44336"
                            )
                            resetFramePipeline()
                            isProcessing = false
                        }
                    }
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Error en processFaceLogin: ${e.message}", e)
                resetFramePipeline()
                isProcessing = false
            }
        }.start()
    }

    private fun rotateBitmap(bitmap: Bitmap, rotationDegrees: Int): Bitmap {
        if (rotationDegrees == 0) return bitmap
        val matrix = Matrix()
        matrix.postRotate(rotationDegrees.toFloat())
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startCamera()
            } else {
                Toast.makeText(
                    this,
                    "Se requiere permiso de cámara para el reconocimiento facial",
                    Toast.LENGTH_LONG
                ).show()
                finish()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        isRunning = false
        handler.removeCallbacks(frameRunnable)
        camera2Helper?.stopCamera()
        camera2Helper = null
    }

    override fun onResume() {
        super.onResume()
        if (hasCameraPermission() && !isRunning) {
            startCamera()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        handler.removeCallbacks(frameRunnable)
        camera2Helper?.stopCamera()
        camera2Helper = null
    }

    private fun mirrorBitmap(bitmap: Bitmap): Bitmap {
        val matrix = Matrix().apply { preScale(-1.0f, 1.0f) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }
}