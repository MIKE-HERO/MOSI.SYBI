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
    private val frameIntervalMs = 800L

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

        // Inicializar vistas primero
        textureView = findViewById(R.id.textureView)
        btnBackFaceLogin = findViewById(R.id.btnBackFaceLogin)
        tvStatus = findViewById(R.id.tvFaceLoginStatus)
        sideBar = findViewById(R.id.sideBarLayout)

        // Inicializar modelos DESPUÉS de las vistas
        FaceBiometricsHelper.init(this)

        btnBackFaceLogin.setOnClickListener { finish() }

        // Receptor de color
        val colorReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val newColor = intent.getStringExtra("new_color")
                if (newColor != null) {
                    currentColor = newColor
                    applyColorTheme(newColor)
                }
            }
        }
        LocalBroadcastManager.getInstance(this).registerReceiver(colorReceiver, IntentFilter("ACTION_UPDATE_THEME"))

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
        camera2Helper = Camera2Helper(this, textureView).also { it.startCamera() }

        isRunning = true
        handler.postDelayed(frameRunnable, frameIntervalMs)
    }

    /**
     * Captura un frame del TextureView y lo procesa con MediaPipe + FaceNet.
     */
    private fun captureAndAnalyzeFrame() {
        if (isProcessing) return
        val bitmap = camera2Helper?.takePhoto() ?: return
        if (bitmap.width == 0 || bitmap.height == 0) return

        isProcessing = true

        Thread {
            try {
                val mpResult = MediaPipeFaceHelper.detect(bitmap)
                val hasFace = mpResult != null && mpResult.faceLandmarks().isNotEmpty()

                if (hasFace && mpResult != null) {
                    val liveBiometrics = FaceBiometricsHelper.processFace(bitmap, mpResult)

                    if (liveBiometrics != null) {
                        runOnUiThread {
                            tvStatus.text = "Rostro detectado, verificando..."
                            tvStatus.setTextColor(Color.parseColor("#FF9800"))
                        }
                        processFaceLogin(liveBiometrics)
                    } else {
                        runOnUiThread {
                            tvStatus.text = "Mire fijamente a la cámara"
                            tvStatus.setTextColor(Color.parseColor("#4CAF50"))
                        }
                        isProcessing = false
                    }
                } else {
                    runOnUiThread {
                        tvStatus.text = "Coloque su rostro frente a la cámara"
                        tvStatus.setTextColor(Color.parseColor("#4CAF50"))
                    }
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
                    var mayorSimilitud = 0f
                    val umbralEstricto = 0.72f

                    for (paciente in pacientes) {
                        val fotoGuardada = paciente.foto
                        if (!fotoGuardada.isNullOrEmpty()) {
                            val storedBiometrics = obtenerBiometriaDeFotoGuardada(fotoGuardada)
                            if (storedBiometrics != null) {
                                val similitud = FaceBiometricsHelper.matchFaces(liveBiometrics, storedBiometrics)
                                Log.d(TAG, "Paciente ${paciente.nombre} - Sim: $similitud")
                                if (similitud > mayorSimilitud) {
                                    mayorSimilitud = similitud
                                    mejorPaciente = paciente
                                }
                            }
                        }
                    }

                    val pacienteEncontrado = if (mejorPaciente != null && mayorSimilitud >= umbralEstricto)
                        mejorPaciente else null

                    if (pacienteEncontrado != null) {
                        val paciente = pacienteEncontrado
                        isRunning = false
                        handler.removeCallbacks(frameRunnable)

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
                        runOnUiThread {
                            Toast.makeText(
                                this@FaceLoginActivity,
                                "Rostro no reconocido. Intente nuevamente",
                                Toast.LENGTH_LONG
                            ).show()
                            tvStatus.text = "Rostro no reconocido. Intente de nuevo"
                            tvStatus.setTextColor(Color.parseColor("#F44336"))
                        }
                        Thread.sleep(1500)
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

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startCamera()
            } else {
                Toast.makeText(this, "Se requiere permiso de cámara para el reconocimiento facial", Toast.LENGTH_LONG).show()
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
}