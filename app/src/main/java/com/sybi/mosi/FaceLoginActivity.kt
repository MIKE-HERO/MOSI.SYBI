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
import android.util.Base64
import android.util.Log                          // 🔴 FALTABA
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.sybi.mosi.database.AppDatabase
import com.sybi.mosi.helpers.FaceBiometricsHelper  // 🔴 FALTABA
import com.sybi.mosi.helpers.MediaPipeFaceHelper  // 🔴 FALTABA
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream

@androidx.camera.core.ExperimentalGetImage
class FaceLoginActivity : BaseActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var btnBackFaceLogin: View
    private lateinit var tvStatus: TextView
    private lateinit var sideBar: View

    private var currentColor: String = "#0F3E82"
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private var capturedBitmap: Bitmap? = null
    @Volatile private var isProcessing = false   // 🔴 volatile porque se toca desde varios threads

    companion object {
        private const val CAMERA_PERMISSION_CODE = 100
        private const val TAG = "FaceLoginActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_face_login)

        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)

        // Inicializar vistas primero
        previewView = findViewById(R.id.previewView)
        btnBackFaceLogin = findViewById(R.id.btnBackFaceLogin)
        tvStatus = findViewById(R.id.tvFaceLoginStatus)
        sideBar = findViewById(R.id.sideBarLayout)

        // 🔴 Inicializar modelos DESPUÉS de las vistas (más seguro)
        FaceBiometricsHelper.init(this)

        // ✅ Configurar botón de regresar
        btnBackFaceLogin.setOnClickListener {
            finish()
        }

        // Configurar receptor de color
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

        // Aplicar color guardado
        val prefs = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        val savedColor = prefs.getString("BackgroundColor", "#0F3E82") ?: "#0F3E82"
        currentColor = savedColor
        applyColorTheme(savedColor)

        // Iniciar cámara
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

    // ✅ Función para aplicar color del tema
    private fun applyColorTheme(colorHex: String) {
        val color = Color.parseColor(colorHex)
        sideBar.setBackgroundColor(color)
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                cameraProvider = future.get()
                bindCameraUseCases()
            } catch (e: Exception) {
                Toast.makeText(this, "Error al iniciar cámara: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCameraUseCases() {
        val provider = cameraProvider ?: return
        val preview = Preview.Builder()
            .build()
            .also { preview ->
                preview.setSurfaceProvider(previewView.surfaceProvider)
            }

        val imageAnalysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setTargetResolution(android.util.Size(640, 480))
            .build()

        imageAnalysis.setAnalyzer(ContextCompat.getMainExecutor(this)) { imageProxy ->
            processImageProxy(imageProxy)
        }

        val selector = getAvailableCameraSelector(provider)

        try {
            provider.unbindAll()
            provider.bindToLifecycle(
                this,
                selector,
                preview,
                imageAnalysis
            )
        } catch (e: Exception) {
            Toast.makeText(this, "Error al enlazar cámara: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun getAvailableCameraSelector(provider: ProcessCameraProvider): CameraSelector {
        return try {
            if (provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)) {
                CameraSelector.DEFAULT_FRONT_CAMERA
            } else if (provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) {
                CameraSelector.DEFAULT_BACK_CAMERA
            } else {
                CameraSelector.Builder().build()
            }
        } catch (_: Exception) {
            CameraSelector.Builder().build()
        }
    }

    @androidx.camera.core.ExperimentalGetImage
    private fun processImageProxy(imageProxy: ImageProxy) {
        if (isProcessing) {
            imageProxy.close()
            return
        }

        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }

        try {
            val bitmap = imageProxy.toBitmap()
            val rotatedBitmap = rotateBitmap(bitmap, imageProxy.imageInfo.rotationDegrees)

            // 🔴 MediaPipe
            val mpResult = MediaPipeFaceHelper.detect(rotatedBitmap)
            val hasFace = mpResult != null && mpResult.faceLandmarks().isNotEmpty()

            if (hasFace && mpResult != null) {
                val liveBiometrics = FaceBiometricsHelper.processFace(rotatedBitmap, mpResult)

                if (liveBiometrics != null) {
                    isProcessing = true
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
                }
            } else {
                runOnUiThread {
                    tvStatus.text = "Coloque su rostro frente a la cámara"
                    tvStatus.setTextColor(Color.parseColor("#4CAF50"))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error procesando frame: ${e.message}", e)
            // 🔴 Resetear el flag por si quedó bloqueado
            isProcessing = false
        } finally {
            imageProxy.close()
        }
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
                        runOnUiThread {
                            Toast.makeText(
                                this@FaceLoginActivity,
                                "¡Bienvenido ${paciente.nombre}!",
                                Toast.LENGTH_LONG
                            ).show()

                            // 🔴 RESTAURADO: navegación al perfil
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
                        // 🔴 Resetear fuera del UI thread, con un pequeño delay para evitar re-disparo
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

    @Suppress("unused")
    private fun bitmapToBase64(bitmap: Bitmap): String {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, stream)
        val byteArray = stream.toByteArray()
        return Base64.encodeToString(byteArray, Base64.DEFAULT)
    }

    private fun rotateBitmap(bitmap: Bitmap, rotationDegrees: Int): Bitmap {
        if (rotationDegrees == 0) return bitmap
        val matrix = Matrix()
        matrix.postRotate(rotationDegrees.toFloat())
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    @Suppress("unused")
    private fun resizeBitmap(bitmap: Bitmap, targetWidth: Int, targetHeight: Int): Bitmap {
        val originalWidth = bitmap.width
        val originalHeight = bitmap.height
        val scale = minOf(
            targetWidth.toFloat() / originalWidth,
            targetHeight.toFloat() / originalHeight
        )
        val newWidth = (originalWidth * scale).toInt()
        val newHeight = (originalHeight * scale).toInt()
        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
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
        cameraProvider?.unbindAll()
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraProvider?.unbindAll()
    }
}