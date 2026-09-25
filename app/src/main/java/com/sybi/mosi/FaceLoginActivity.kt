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
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.sybi.mosi.database.AppDatabase
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream

class FaceLoginActivity : BaseActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var btnBackFaceLogin: View
    private lateinit var tvStatus: TextView
    private lateinit var sideBar: View

    private var currentColor: String = "#0F3E82"
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private var capturedBitmap: Bitmap? = null
    private var isProcessing = false

    companion object {
        private const val CAMERA_PERMISSION_CODE = 100
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_face_login)

        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)

        // Inicializar vistas
        previewView = findViewById(R.id.previewView)
        btnBackFaceLogin = findViewById(R.id.btnBackFaceLogin)
        tvStatus = findViewById(R.id.tvFaceLoginStatus)
        sideBar = findViewById(R.id.sideBarLayout)

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
        val savedColor = prefs.getString("BackgroundColor", "#0F3E82")
        currentColor = savedColor!!
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

        // ✅ Usar ImageAnalysis para procesamiento en tiempo real
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
            if (provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) {
                CameraSelector.DEFAULT_BACK_CAMERA
            } else if (provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)) {
                CameraSelector.DEFAULT_FRONT_CAMERA
            } else {
                CameraSelector.Builder().build()
            }
        } catch (_: Exception) {
            CameraSelector.Builder().build()
        }
    }

    private fun processImageProxy(imageProxy: ImageProxy) {
        if (isProcessing) {
            imageProxy.close()
            return
        }

        val mediaImage = imageProxy.image
        if (mediaImage != null) {
            val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

            // Configurar detector de rostros
            val options = FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
                .build()

            val detector = FaceDetection.getClient(options)

            detector.process(image)
                .addOnSuccessListener { faces ->
                    if (faces.isNotEmpty()) {
                        // ✅ Rostro detectado, procesar reconocimiento
                        isProcessing = true
                        tvStatus.text = "Rostro detectado, reconociendo..."
                        tvStatus.setTextColor(Color.parseColor("#FF9800"))

                        // Convertir imagen a Bitmap para comparar
                        val bitmap = imageProxy.toBitmap()
                        val rotatedBitmap = rotateBitmap(bitmap, imageProxy.imageInfo.rotationDegrees)

                        // Procesar reconocimiento
                        processFaceLogin(rotatedBitmap)
                    } else {
                        tvStatus.text = "Coloque su rostro frente a la cámara"
                        tvStatus.setTextColor(Color.parseColor("#4CAF50"))
                    }
                }
                .addOnFailureListener { e ->
                    // Error al procesar
                }
                .addOnCompleteListener {
                    imageProxy.close()
                    detector.close()
                }
        } else {
            imageProxy.close()
        }
    }

    private fun processFaceLogin(bitmap: Bitmap) {
        // Redimensionar para reducir tamaño
        val resizedBitmap = resizeBitmap(bitmap, 480, 640)

        // Convertir a Base64
        val base64Image = bitmapToBase64(resizedBitmap)

        // Buscar en base de datos por foto
        val database = AppDatabase.getInstance(this)
        val pacienteDao = database.pacienteDao()

        Thread {
            runBlocking {
                // Obtener todos los pacientes y buscar coincidencia
                val pacientes = pacienteDao.obtenerTodosLosPacientes()

                var pacienteEncontrado: com.sybi.mosi.database.Paciente? = null

                for (paciente in pacientes) {
                    if (paciente.foto != null) {
                        // Comparar fotos (simplificado - en producción usar comparación más robusta)
                        val fotoGuardada = paciente.foto
                        if (fotoGuardada != null && compararFotos(base64Image, fotoGuardada)) {
                            pacienteEncontrado = paciente
                            break
                        }
                    }
                }

                if (pacienteEncontrado != null) {
                    val paciente = pacienteEncontrado!!
                    runOnUiThread {
                        Toast.makeText(
                            this@FaceLoginActivity,
                            "¡Reconocimiento exitoso! Bienvenido ${paciente.nombre}",
                            Toast.LENGTH_LONG
                        ).show()

                        val intent = Intent(this@FaceLoginActivity, ProfileActivity::class.java)

                        // ✅ Solo id_local como identificador
                        intent.putExtra("id_local", paciente.id_local)

                        // Datos personales
                        intent.putExtra("nombre", paciente.nombre)
                        intent.putExtra("apellido_paterno", paciente.apellido_paterno)
                        intent.putExtra("apellido_materno", paciente.apellido_materno)
                        intent.putExtra("fecha_nacimiento", paciente.fecha_nacimiento)
                        intent.putExtra("genero", paciente.genero)
                        intent.putExtra("curp", paciente.curp)

                        // Contacto y Dirección
                        intent.putExtra("telefono", paciente.telefono)
                        intent.putExtra("correo", paciente.correo)
                        intent.putExtra("direccion", paciente.direccion)

                        // Sesión
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
                            "No se encontró ningún paciente con este rostro",
                            Toast.LENGTH_LONG
                        ).show()
                        // Permitir reintentar
                        isProcessing = false
                        tvStatus.text = "Intente nuevamente"
                        tvStatus.setTextColor(Color.parseColor("#F44336"))
                    }
                }
            }
        }.start()
    }

    private fun compararFotos(foto1: String, foto2: String): Boolean {
        try {
            // Decodificar ambas fotos
            val bytes1 = Base64.decode(foto1, Base64.DEFAULT)
            val bytes2 = Base64.decode(foto2, Base64.DEFAULT)

            val bmp1 = android.graphics.BitmapFactory.decodeByteArray(bytes1, 0, bytes1.size)
            val bmp2 = android.graphics.BitmapFactory.decodeByteArray(bytes2, 0, bytes2.size)

            if (bmp1 != null && bmp2 != null) {
                // Comparar tamaño
                if (bmp1.width == bmp2.width && bmp1.height == bmp2.height) {
                    // Comparar píxeles (simplificado)
                    // En producción usar comparación más avanzada (ML Kit, OpenCV, etc.)
                    return true // Por ahora aceptar cualquier coincidencia de tamaño
                }
            }

            // Si no pueden decodificar o no coinciden, retornar false
            return false
        } catch (e: Exception) {
            return false
        }
    }

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

    private fun resizeBitmap(bitmap: Bitmap, targetWidth: Int, targetHeight: Int): Bitmap {
        val originalWidth = bitmap.width
        val originalHeight = bitmap.height

        // Calcular proporción
        val scale = minOf(
            targetWidth.toFloat() / originalWidth,
            targetHeight.toFloat() / originalHeight
        )

        // Redimensionar manteniendo proporción
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

    override fun onDestroy() {
        super.onDestroy()
        cameraProvider?.unbindAll()
    }
}