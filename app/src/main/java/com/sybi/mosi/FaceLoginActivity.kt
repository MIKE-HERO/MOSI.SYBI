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
import com.sybi.mosi.database.AppDatabase
import com.sybi.mosi.helpers.Camera2Helper
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
    private val frameIntervalMs = 700L

    // ============================================================
    // UMBRALES de similitud coseno CRUDA (rango -1..1)
    // Ajusta según tu modelo:
    //   ArcFace / MobileFaceNet: mismo ≥ 0.60, distinto < 0.40
    //   FaceNet original:        mismo ≥ 0.80, distinto < 0.55
    // ============================================================
    private val UMBRAL_ACEPTAR = 0.90f     // para confirmar identidad
    private val UMBRAL_RECHAZAR = 0.55f    // por debajo → seguro distinto

    // Voto por mayoría: acumula similitudes de N frames antes de decidir
    private val FRAMES_PARA_CONFIRMAR = 5
    // Mapa: pacienteId → lista de similitudes recientes
    private val recentSimilarities = mutableMapOf<Long, MutableList<Float>>()

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

    private fun startCamera() {
        camera2Helper?.stopCamera()
        textureView.scaleX = -1f
        camera2Helper = Camera2Helper(this, textureView).also { it.startCamera() }

        isRunning = true
        recentSimilarities.clear()
        currentUiState = UiState.NO_FACE
        lastUiStateChangeMs = 0L
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
                        isProcessing = false
                    }
                } else {
                    setUiState(
                        UiState.NO_FACE,
                        "Coloque su rostro frente a la cámara",
                        "#4CAF50"
                    )
                    isProcessing = false
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error procesando frame: ${e.message}", e)
                isProcessing = false
            }
        }.start()
    }

    private fun processFaceLogin(liveBiometrics: FaceBiometricsHelper.BiometricFaceData) {
        val database = AppDatabase.getInstance(this)
        val pacienteDao = database.pacienteDao()

        Thread {
            runBlocking {
                try {
                    val pacientes = pacienteDao.obtenerTodosLosPacientes()

                    var mejorPaciente: com.sybi.mosi.database.Paciente? = null
                    var mayorSimilitud = -1f

                    for (paciente in pacientes) {
                        val fotoGuardada = paciente.foto
                        if (!fotoGuardada.isNullOrEmpty()) {
                            val storedBiometrics = obtenerBiometriaDeFotoGuardada(fotoGuardada)
                            if (storedBiometrics != null) {
                                val similitud = FaceBiometricsHelper.matchFaces(
                                    liveBiometrics, storedBiometrics
                                )
                                Log.d(TAG, "Paciente ${paciente.nombre} - Sim: $similitud")

                                // Acumular en el mapa de votos
                                val id = paciente.id_local
                                val list = recentSimilarities.getOrPut(id) { mutableListOf() }
                                list.add(similitud)
                                if (list.size > FRAMES_PARA_CONFIRMAR) list.removeAt(0)

                                if (similitud > mayorSimilitud) {
                                    mayorSimilitud = similitud
                                    mejorPaciente = paciente
                                }
                            }
                        }
                    }

                    // === Decisión con voto por mayoría ===
                    var pacienteConfirmado: com.sybi.mosi.database.Paciente? = null

                    if (mejorPaciente != null && mayorSimilitud >= UMBRAL_ACEPTAR) {
                        val id = mejorPaciente.id_local
                        val historial = recentSimilarities[id] ?: mutableListOf()
                        val promedio = if (historial.isNotEmpty()) historial.average().toFloat() else 0f

                        Log.d(
                            TAG,
                            "Candidato: ${mejorPaciente.nombre}, sim actual: $mayorSimilitud, " +
                                    "promedio: $promedio, frames: ${historial.size}"
                        )

                        // Confirmar si tenemos suficientes frames y el promedio también supera el umbral
                        if (historial.size >= FRAMES_PARA_CONFIRMAR && promedio >= UMBRAL_ACEPTAR) {
                            pacienteConfirmado = mejorPaciente
                        }
                    }

                    if (pacienteConfirmado != null) {
                        val paciente = pacienteConfirmado
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
                        // Si el match más alto es claramente bajo, avisar al usuario
                        if (mayorSimilitud < UMBRAL_RECHAZAR) {
                            setUiState(
                                UiState.NOT_RECOGNIZED,
                                "Rostro no reconocido. Intente nuevamente",
                                "#F44336"
                            )
                        } else {
                            setUiState(
                                UiState.PROCESSING,
                                "Verificando... mantenga la posición",
                                "#FF9800"
                            )
                        }
                        isProcessing = false
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error en processFaceLogin: ${e.message}", e)
                    isProcessing = false
                }
            }
        }.start()
    }

    private fun obtenerBiometriaDeFotoGuardada(base64Foto: String): FaceBiometricsHelper.BiometricFaceData? {
        return try {
            val bytes = Base64.decode(base64Foto, Base64.DEFAULT)
            val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
            val mpResult = MediaPipeFaceHelper.detect(bmp) ?: return null
            if (mpResult.faceLandmarks().isEmpty()) return null
            FaceBiometricsHelper.processFace(bmp, mpResult)
        } catch (e: Exception) {
            Log.e(TAG, "Error extrayendo biometría guardada: ${e.message}", e)
            null
        }
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